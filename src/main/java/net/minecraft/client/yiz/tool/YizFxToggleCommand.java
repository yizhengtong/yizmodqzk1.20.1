package net.minecraft.client.yiz.tool;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.fml.loading.FMLEnvironment;

/**
 * <b>【调试指令】</b>{@code /yiz fxtoggle …} —— 闪电特效的开关。
 * 开关本体在 {@link FxDebugSwitches}（客户端内存态，不做网络同步）。
 *
 * <p><b>只剩两个槽位</b>（用户逐项实测 1..9 后的定案：真正有用的只有这两个）：</p>
 * <ul>
 *   <li>{@code 1} 闪电链（两实体之间的链式电弧）—— <b>默认开</b>；</li>
 *   <li>{@code 4} 围弧（感电期间<b>飘在实体身体外那一圈空间里</b>跳动的小弧群，<b>跟随模型</b>：
 *       走动/转身/击飞倒下都贴合；几何是独立折线，端点取自模型顶点采出的锚点，
 *       见 {@code ShockArcs}）—— <b>默认开</b>。</li>
 * </ul>
 * <p>其余编号（2/3/5/6/7/8/9）对应的表现<b>已全部删除</b>，敲它们会回一句明确的"该表现已删除"
 * 并指路到 1/4，而不是"未知编号" —— 用户就是拿这些编号逐个测的，得给个明确答复。</p>
 *
 * <p>用法（全部子指令都能 tab 补全）：</p>
 * <ul>
 *   <li>{@code /yiz fxtoggle 1|4} —— <b>切换</b>对应表现（关掉时顺手清掉已在飞的实例）；</li>
 *   <li>{@code /yiz fxtoggle}（不带参数）—— 状态 + 用法；</li>
 *   <li>{@code /yiz fxtoggle status} —— 列出两项状态（应只有 1 与 4，且都默认开）；</li>
 *   <li>{@code /yiz fxtoggle all on|off} —— 一次全开 / 全关（{@code off} 后屏幕上应当<b>一个闪电都不剩</b>）；</li>
 *   <li>{@code /yiz fxtoggle probe} —— 立刻把<b>当前渲染现场</b>打到聊天栏 + 日志
 *       （绑定的 FBO / 是否主目标 / viewport / 描边 FBO / 纹理单元 0 / 当前着色器 / 玩家身上的效果）。
 *       画面异常<b>当场</b>敲这一条最有用。</li>
 * </ul>
 *
 * <p>指令沿用的是本模组既有的注册方式（{@link SimpleCommandRegistry}，在服务端
 * {@code RegisterCommandsEvent} 上注册）：整合服务器与客户端同 JVM，所以服务端指令能直接切到
 * 客户端渲染线程读的那个静态开关；专用服务器上执行会安全失败（不崩服）。</p>
 */
public final class YizFxToggleCommand {

    private YizFxToggleCommand() {}

    public static void register() {
        // 编号节点只挂 1 与 4（两项的编号保持不变）：字面量节点，tab 补全能直接列出
        LiteralArgumentBuilder<CommandSourceStack> fx = Commands.literal("fxtoggle")
                .executes(YizFxToggleCommand::usage)
                .then(Commands.literal(Integer.toString(FxDebugSwitches.CHAIN_ARC))
                        .executes(ctx -> toggle(ctx, FxDebugSwitches.CHAIN_ARC)))
                .then(Commands.literal(Integer.toString(FxDebugSwitches.SURFACE_ARCS))
                        .executes(ctx -> toggle(ctx, FxDebugSwitches.SURFACE_ARCS)))
                // 已删除的编号也给节点：敲了能得到明确回显（而不是 briggadier 的"未知参数"）
                .then(Commands.literal("2").executes(ctx -> toggle(ctx, 2)))
                .then(Commands.literal("3").executes(ctx -> toggle(ctx, 3)))
                .then(Commands.literal("5").executes(ctx -> toggle(ctx, 5)))
                .then(Commands.literal("6").executes(ctx -> toggle(ctx, 6)))
                .then(Commands.literal("7").executes(ctx -> toggle(ctx, 7)))
                .then(Commands.literal("8").executes(ctx -> toggle(ctx, 8)))
                .then(Commands.literal("9").executes(ctx -> toggle(ctx, 9)));
        fx = fx
                .then(Commands.literal("status").executes(YizFxToggleCommand::status))
                .then(Commands.literal("all")
                        .then(Commands.literal("on").executes(ctx -> setAll(ctx, true)))
                        .then(Commands.literal("off").executes(ctx -> setAll(ctx, false))))
                .then(Commands.literal("probe").executes(YizFxToggleCommand::probe));
        SimpleCommandRegistry.register(Commands.literal("yiz").then(fx));
    }

    /** 不带参数：状态 + 用法。 */
    private static int usage(CommandContext<CommandSourceStack> ctx) {
        sendStatus(ctx);
        ctx.getSource().sendSuccess(() -> Component.literal(
                "§7用法: §f/yiz fxtoggle §a<1|4>§7 切换单项（§f1§7 = 闪电链；§f4§7 = 围弧·跟随模型）；"
                        + "§f/yiz fxtoggle status §7看状态；§f/yiz fxtoggle all on|off §7全开/全关；"
                        + "§f/yiz fxtoggle probe §7打印渲染现场"), false);
        return Command.SINGLE_SUCCESS;
    }

    /** 切换一个槽位并回显。 */
    private static int toggle(CommandContext<CommandSourceStack> ctx, int slot) {
        if (!clientGuard(ctx)) return 0;
        // ── 已删除的编号（2/3/5/6/7/8/9）：给一句明确的回显，而不是"未知编号" ──
        if (FxDebugSwitches.isRemoved(slot)) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "§6[特效开关] §7编号 §f" + slot + " §7对应的表现§c已删除§7（用户实测确认无用）；"
                            + "现在只剩 §f1 " + FxDebugSwitches.name(FxDebugSwitches.CHAIN_ARC)
                            + "§7 与 §f4 " + FxDebugSwitches.name(FxDebugSwitches.SURFACE_ARCS)
                            + "§7。状态： " + FxDebugSwitches.coloredStatus()), false);
            return Command.SINGLE_SUCCESS;
        }
        Boolean now = FxDebugSwitches.toggle(slot);
        if (now == null) {
            ctx.getSource().sendFailure(Component.literal(
                    "§c未知特效编号: " + slot + "（只有 1 与 4）"));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(
                "§6[特效开关] §f" + slot + " " + FxDebugSwitches.name(slot) + " → " + (now ? "§a开" : "§c关")
                        + "  §7|  " + FxDebugSwitches.coloredStatus()), false);
        return Command.SINGLE_SUCCESS;
    }

    /** 只回显状态。 */
    private static int status(CommandContext<CommandSourceStack> ctx) {
        sendStatus(ctx);
        return Command.SINGLE_SUCCESS;
    }

    /** 全开 / 全关（全关后屏幕上应当一个闪电都不剩 —— 见 {@code FxDebugSwitches} 类注释的"硬性验收"）。 */
    private static int setAll(CommandContext<CommandSourceStack> ctx, boolean on) {
        if (!clientGuard(ctx)) return 0;
        FxDebugSwitches.setAll(on);
        ctx.getSource().sendSuccess(() -> Component.literal(
                "§6[特效开关] §f全部" + (on ? "打开" : "关闭")
                        + (on ? "" : " §7（已在飞的弧也一并清掉了）")), false);
        sendStatus(ctx);
        return Command.SINGLE_SUCCESS;
    }

    /** 状态：标题 + 两行（1 与 4，都默认开）+ 围弧现场一行。 */
    private static void sendStatus(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().sendSuccess(() -> Component.literal(
                "§6[特效开关] §7状态（只剩两项，默认都开）——"), false);
        for (String row : FxDebugSwitches.statusRows()) {
            ctx.getSource().sendSuccess(() -> Component.literal("  " + row), false);
        }
        // 围弧现场（编号 4）：一眼看出"现在到底有没有弧在画"——感电时敲一次就能确认
        // 锚点表建起来了（实体≥1）、弧在补生（弧>0）。三类正常的"弧=0"：没有实体在感电 /
        // 开关 4 关着 / 感电实体本帧没被渲染（视锥剔除、第一人称就是自己）。
        ctx.getSource().sendSuccess(() -> Component.literal(
                "  §b4 围弧现场§7: §r" + net.minecraft.client.yiz.lightning.fx.ShockArcs.debugSummary()), false);
    }

    /**
     * 现场取证：把当前渲染现场打到聊天栏 + 日志。
     *
     * <p>这里必须绕一圈：{@code GlStateManager}/{@code GL11} 只能在<b>渲染线程</b>读（自带线程断言），
     * 而指令回调跑在服务端线程 —— 所以用 {@code Minecraft#execute} 抛回客户端线程读，
     * 再往聊天栏发一条（聊天栏写入也必须在客户端线程）。</p>
     */
    private static int probe(CommandContext<CommandSourceStack> ctx) {
        if (!clientGuard(ctx)) return 0;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            ctx.getSource().sendFailure(Component.literal("§c当前没有客户端玩家，稍后再试"));
            return 0;
        }
        // 顺手把"描边 FBO 掩码回读"再放行一次：那一条默认只在第一次跑到 lock-post 时打一次，
        // 而画面异常是**当场**才有的现象 —— 每次 probe 都重新采一帧，日志里才有"那一刻"的数字。
        net.minecraft.client.yiz.modelmirror.RenderTargetProbe.requestCoverageSample();
        mc.execute(() -> {
            String line = net.minecraft.client.yiz.modelmirror.RenderTargetProbe.describe("probe");
            net.minecraft.client.yiz.modelmirror.RenderTargetProbe.logNow("probe");
            mc.gui.getChat().addMessage(Component.literal("§6[FxProbe] §f" + line));
        });
        ctx.getSource().sendSuccess(() -> Component.literal(
                "§a已采集当前渲染现场（聊天栏 + logs/latest.log 的 §f[FxBlack] §a行）；"
                        + "本帧还会额外回读一次描边 FBO —— 下一帧日志里那条 §f[FxBlack] 描边 FBO 回读§a 中，"
                        + "看 §f★后处理涂色占比§a（修好后应是个位数）与 §f实心度§a"), false);
        return Command.SINGLE_SUCCESS;
    }

    /** 客户端视觉开关只对客户端有意义：专用服务器上执行安全失败（与 /yiz flame 一致）。 */
    private static boolean clientGuard(CommandContext<CommandSourceStack> ctx) {
        if (!FMLEnvironment.dist.isClient()) {
            ctx.getSource().sendFailure(Component.literal("§c特效开关是客户端视觉，专用服务器上无效"));
            return false;
        }
        return true;
    }
}
