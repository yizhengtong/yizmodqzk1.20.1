package net.minecraft.client.yiz.tool;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.client.Minecraft;
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
        SimpleCommandRegistry.register(Commands.literal("yiz").then(flame));
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
