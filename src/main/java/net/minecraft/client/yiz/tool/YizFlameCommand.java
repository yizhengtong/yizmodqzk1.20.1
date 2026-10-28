package net.minecraft.client.yiz.tool;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.yiz.flame.FlameStylePreset;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * 体积火焰测试指令：{@code /yiz flame <形态> [world|follow] | off}。
 *
 * <p>形态：{@code wall} 火墙 / {@code ring} 火环 / {@code rope} 火绳 /
 * {@code burning} 着火（<b>贴实体实际模型</b>）/ {@code patches} 着火（旧版碰撞箱补丁簇，用于对比）/ {@code shell} 火壳。</p>
 *
 * <p><b>观感预设</b>：{@code /yiz flame preset <名字>} 可在游戏里直接换一整套观感
 * （经典/Classic / 火把 / 营火 / 烈焰喷射 / 蓝焰 / 余烬，见 {@link FlameStylePreset}），
 * {@code /yiz flame preset list} 列全部。预设只改"粗细/快慢/浓淡/明暗/颜色/火舌长度/火星"，
 * <b>不改"火焰多高"</b>（那是写死在着色器里的硬约束：不超过目标身高一半）。
 * 旧名 {@code candle} 仍可用，会切到 {@code classic}（同一套参数）。</p>
 *
 * <p>{@code burning} 与 {@code patches} 的区别（方便左右对比观感）：</p>
 * <ul>
 *   <li>{@code burning}：{@code FlameEffects.burningSurface(...)} —— 火焰几何 = 实体自己的模型几何
 *       （头/躯干/四肢，GeckoLib 自定义模型同理），从下往上爬满；自己着火 + 第一人称时
 *       2.0 秒后停止蔓延，并自动挂一套体积火兜底（原版第一人称不渲染本地玩家模型）；</li>
 *   <li>{@code patches}：{@code FlameEffects.burning(...)} —— 旧版在碰撞箱周围陆续冒出的小火团，
 *       不贴合模型。两者互不影响，可同时生成。</li>
 * </ul>
 *
 * <p>连接方式（对应 {@code FlameEffects.Anchor}）：</p>
 * <ul>
 *   <li>{@code world}（默认）<b>客观世界坐标固定</b> —— 在生成点原地燃烧，走远也不会跟过来；</li>
 *   <li>{@code follow} <b>追踪实体实时位置</b> —— 挂在最近的实体（没有则自己）身上，
 *       位置、轴向、噪声花纹一起跟随（转身时火也跟着转）。</li>
 * </ul>
 *
 * <p>效果是<b>客户端视觉</b>：整合服务器与客户端同 JVM（单人/开发环境）时直接在客户端入队；
 * 专用服务器上执行会安全失败（不会崩服）。</p>
 */
public final class YizFlameCommand {

    private static final int DEFAULT_LIFE = 300;
    /** 着火（贴模型）爬满全身所需的 tick：160 ≈ 8 秒，和旧版补丁簇"约 8 秒爬满"的节奏一致。 */
    private static final int BURNING_SPREAD_TICKS = 160;
    /** 着火整体寿命（tick）：600 = 30 秒后自动淡出。 */
    private static final int BURNING_LIFE = 600;

    private YizFlameCommand() {}

    public static void register() {
        LiteralArgumentBuilder<CommandSourceStack> flame = Commands.literal("flame");
        for (String shape : new String[]{"wall", "ring", "rope", "burning", "patches", "shell"}) {
            flame.then(Commands.literal(shape)
                .executes(ctx -> spawn(ctx, shape, false))
                .then(Commands.literal("world").executes(ctx -> spawn(ctx, shape, false)))
                .then(Commands.literal("follow").executes(ctx -> spawn(ctx, shape, true))));
        }
        flame.then(Commands.literal("off").executes(YizFlameCommand::clear));
        flame.then(presetNode());
        SimpleCommandRegistry.register(Commands.literal("yiz").then(flame));
    }

    /**
     * {@code /yiz flame preset <名字>} —— 切换"火焰观感预设"（{@link FlameStylePreset}），
     * 外加 {@code /yiz flame preset list} 列出全部。
     *
     * <p>为什么做它：用户在"想要哪种火"这件事上给不出参数化的描述（"就想要现实里那种火"），
     * 但完全能指着某一种说"我要这个"。有了这条指令，说明书里的 6 种预设可以<b>当场看</b>，
     * 不用改代码重新构建。</p>
     *
     * <p>它只改客户端的 uniform 值（粗细/快慢/浓淡/明暗/颜色/火舌长度/火星），
     * <b>不动"火焰多高"</b> —— 那是用户要求过的硬约束（不超过身高一半），写死在着色器常量里。</p>
     */
    private static LiteralArgumentBuilder<CommandSourceStack> presetNode() {
        LiteralArgumentBuilder<CommandSourceStack> preset = Commands.literal("preset");
        for (var p : FlameStylePreset.all()) {
            preset.then(Commands.literal(p.id).executes(ctx -> selectPreset(ctx, p.id)));
        }
        // 旧名别名（如 candle → classic）：不注册对应预设，只注册一个指向正式 id 的字面量节点，
        //  于是 `/yiz flame preset candle` 与 `/yiz flame preset classic` 完全等价。
        for (String alias : FlameStylePreset.aliases()) {
            preset.then(Commands.literal(alias).executes(ctx -> selectPreset(ctx, alias)));
        }
        preset.then(Commands.literal("list").executes(YizFlameCommand::listPresets));
        preset.executes(YizFlameCommand::listPresets);   // 不带参数 = 列出全部
        return preset;
    }

    /** 切换预设：只动客户端视觉状态，服务端/专用服务器上直接拒绝（不假装成功）。 */
    private static int selectPreset(CommandContext<CommandSourceStack> ctx, String id) {
        if (!net.minecraftforge.fml.loading.FMLEnvironment.dist.isClient()) {
            ctx.getSource().sendFailure(Component.literal("§c火焰预设是客户端视觉，专用服务器上无效"));
            return 0;
        }
        FlameStylePreset p = FlameStylePreset.select(id);
        if (p == null) {
            ctx.getSource().sendFailure(Component.literal("§c未知预设: " + id + "（用 /yiz flame preset list 看全部）"));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(
                "§a已切换火焰预设: §f" + p.label + " §7(" + p.id + ")"
                + (FlameStylePreset.isAlias(id) ? "\n§7（§f" + id + "§7 是旧名别名 → §f" + p.id
                        + "§7，参数完全一样）" : "")
                + "\n§7" + p.blurb
                + "\n§7参数: " + FlameStylePreset.describe()
                + "\n§7已经烧起来的贴模型火会当帧变化；体积补丁（旧版着火/火星）在下次生成时用新参数。"), false);
        return 1;
    }

    /** 列出全部预设（带一句话观感），供用户挑。 */
    private static int listPresets(CommandContext<CommandSourceStack> ctx) {
        StringBuilder sb = new StringBuilder("§b火焰预设（§f/yiz flame preset <名字>§b 切换，当前 = ")
                .append(FlameStylePreset.active().label).append("§b）:");
        for (var p : FlameStylePreset.all()) {
            sb.append("\n§7 - §f").append(p.id).append("§7（").append(p.label).append("）：").append(p.blurb);
        }
        // 旧名别名单独标一行（不占预设条目、不改上面的顺序）：candle = classic（旧名别名）
        for (String alias : FlameStylePreset.aliases()) {
            sb.append("\n§7   ↳ §f").append(alias).append("§7 = §f")
              .append(FlameStylePreset.canonicalId(alias)).append("§7（旧名别名），参数相同");
        }
        sb.append("\n§7看效果：§f/yiz flame burning follow§7（跟在自己身上）或 §f/yiz flame burning");
        ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
        return FlameStylePreset.all().size();
    }

    private static int spawn(CommandContext<CommandSourceStack> ctx, String kind, boolean follow) {
        if (!net.minecraftforge.fml.loading.FMLEnvironment.dist.isClient()) {
            ctx.getSource().sendFailure(Component.literal("§c体积火焰是客户端视觉，专用服务器上无效"));
            return 0;
        }
        Player player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendFailure(Component.literal("§c此指令只能由玩家执行"));
            return 0;
        }
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null) return 0;

            Vec3 eye = player.getEyePosition();
            Vec3 look = player.getLookAngle();
            Vec3 flat = new Vec3(look.x, 0.0, look.z).normalize();
            Vec3 right = new Vec3(-flat.z, 0.0, flat.x);   // 水平右向量：火墙沿它展开
            Vec3 feet = player.position();

            // follow 模式：挂到最近的实体（找不到就自己）；着火两种形态都必须追踪实体
            Entity target = null;
            if (follow || "burning".equals(kind) || "patches".equals(kind)) {
                target = nearestEntity(mc, player, feet, 8.0);
                if (target == null) target = player;
            }

            switch (kind) {
                case "wall" -> {
                    var f = net.minecraft.client.yiz.flame.FlameEffects
                            .wall(feet.add(flat.scale(4.0)), right, 7.0f, 3.2f, 0.8f, DEFAULT_LIFE);
                    if (follow) f.follow(target, target.getBbHeight() * 0.5, true);
                }
                case "ring" -> {
                    var f = net.minecraft.client.yiz.flame.FlameEffects
                            .ring(feet.add(flat.scale(3.0)), 2.6f, 2.8f, DEFAULT_LIFE);
                    if (follow) f.follow(target, 0.0, true);
                }
                case "rope" -> {
                    List<Vec3> pts = new ArrayList<>(8);
                    Vec3 origin = follow ? target.position() : eye;
                    Vec3 dir = follow ? new Vec3(target.getLookAngle().x, 0.0, target.getLookAngle().z).normalize() : flat;
                    Vec3 side = new Vec3(-dir.z, 0.0, dir.x);
                    for (int i = 0; i < 8; i++) {
                        double t = i / 7.0;
                        double sag = Math.sin(t * Math.PI) * 0.9;      // 中间下垂，像甩出去的绳
                        double wobble = Math.sin(t * Math.PI * 2.0) * 0.5;
                        pts.add(origin.add(dir.scale(0.6 + t * 8.0))
                                      .add(side.scale(wobble))
                                      .add(0.0, 1.0 - sag, 0.0));
                    }
                    var f = net.minecraft.client.yiz.flame.FlameEffects.rope(pts, 0.45f, DEFAULT_LIFE);
                    //  火绳的 follow 只是"点集按实体相对重建"，不叠加 followYOffset（点集自带高度）
                    if (follow) f.follow(target, 0.0, true);
                }
                // 新版"着火"：贴实体自己的模型几何（头/躯干/四肢），从下往上爬满
                case "burning" -> net.minecraft.client.yiz.flame.FlameEffects.burningSurface(
                        target,
                        BURNING_SPREAD_TICKS,
                        BURNING_LIFE);
                // 旧版"着火"：碰撞箱周围陆续冒出小团火焰（保留下来做对比，行为一字未改）
                case "patches" -> net.minecraft.client.yiz.flame.FlameEffects.burning(
                        target,
                        10,      // 最多 10 团小火焰（随时间陆续冒出）
                        14,      // 每 14 tick（≈0.7 秒）冒一团 → 约 8 秒爬满
                        BURNING_LIFE);
                case "shell" -> net.minecraft.client.yiz.flame.FlameEffects.fireShell(
                        target,
                        (float) Math.max(target.getBbWidth(), 0.4) * 1.6f,
                        (float) target.getBbHeight() * 1.3f,
                        DEFAULT_LIFE);
                default -> {
                    ctx.getSource().sendFailure(Component.literal("§c未知形态: " + kind));
                    return 0;
                }
            }
            boolean tracksEntity = follow || "burning".equals(kind) || "patches".equals(kind);
            String mode = tracksEntity ? "§e追踪实体" : "§b世界固定";
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "§a已生成体积火焰: §f" + kind + " §7(" + mode + "§7)"), false);
            return 1;
        } catch (Throwable t) {
            ctx.getSource().sendFailure(Component.literal("§c生成失败: " + t));
            return 0;
        }
    }

    private static Entity nearestEntity(Minecraft mc, Player player, Vec3 feet, double radius) {
        List<Entity> found = mc.level.getEntities(player, new AABB(feet, feet).inflate(radius));
        Entity best = null;
        double bestDist = Double.MAX_VALUE;
        for (Entity e : found) {
            if (e == player || !e.isAlive()) continue;
            double d = e.distanceToSqr(feet);
            if (d < bestDist) { bestDist = d; best = e; }
        }
        return best;
    }

    private static int clear(CommandContext<CommandSourceStack> ctx) {
        if (!net.minecraftforge.fml.loading.FMLEnvironment.dist.isClient()) return 0;
        try {
            net.minecraft.client.yiz.flame.FlameEffects.clear();
            ctx.getSource().sendSuccess(() -> Component.literal("§a已清除全部体积火焰"), false);
            return 1;
        } catch (Throwable t) {
            return 0;
        }
    }
}
