package net.minecraft.client.yiz.tool;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * /yiz diag — 诊断日志开关。
 *
 * <p>子指令：</p>
 * <ul>
 *   <li>{@code /yiz diag list}：列出全部诊断项、当前状态与它当初排查的问题。</li>
 *   <li>{@code /yiz diag on|off}：总开关，一次打开/静默全部诊断项。</li>
 *   <li>{@code /yiz diag <id> on|off|reset}：单项开关（reset 恢复默认，已解决的回到关闭）。</li>
 * </ul>
 *
 * <p>登记表在 {@link YizDiagnostics}：新增排查日志时在那里登记一项，
 * 不要在业务代码里直接调 logger.warn。</p>
 */
public final class YizDiagCommand {

    private YizDiagCommand() {}

    public static void register() {
        LiteralArgumentBuilder<CommandSourceStack> cmd = Commands.literal("yiz")
            .then(Commands.literal("diag")
                .then(Commands.literal("list").executes(YizDiagCommand::list))
                .then(Commands.literal("on").executes(ctx -> setGlobal(ctx, true)))
                .then(Commands.literal("off").executes(ctx -> setGlobal(ctx, false)))
                .then(Commands.argument("id", StringArgumentType.word())
                    .then(Commands.literal("on").executes(ctx -> setOne(ctx, true)))
                    .then(Commands.literal("off").executes(ctx -> setOne(ctx, false)))
                    .then(Commands.literal("reset").executes(YizDiagCommand::resetOne))));
        SimpleCommandRegistry.register(cmd);
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        var source = ctx.getSource();
        source.sendSuccess(() -> Component.literal("§6诊断日志总开关："
            + (YizDiagnostics.isGlobalEnabled() ? "§a开" : "§c关")), false);
        for (YizDiagnostics.Entry entry : YizDiagnostics.entries()) {
            boolean on = YizDiagnostics.isOn(entry.id());
            source.sendSuccess(() -> Component.literal(
                (on ? "§a[√] " : "§7[ ] ") + "§f" + entry.id() + " §8— " + entry.note()), false);
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int setGlobal(CommandContext<CommandSourceStack> ctx, boolean on) {
        YizDiagnostics.setGlobalEnabled(on);
        ctx.getSource().sendSuccess(() -> Component.literal(
            "§a诊断日志总开关已" + (on ? "打开" : "关闭")), true);
        return Command.SINGLE_SUCCESS;
    }

    private static int setOne(CommandContext<CommandSourceStack> ctx, boolean on) {
        String id = StringArgumentType.getString(ctx, "id");
        if (YizDiagnostics.entries().stream().noneMatch(e -> e.id().equals(id))) {
            ctx.getSource().sendFailure(Component.literal("§c未知诊断项: " + id + "（用 /yiz diag list 查看）"));
            return 0;
        }
        YizDiagnostics.setOn(id, on);
        ctx.getSource().sendSuccess(() -> Component.literal(
            "§a诊断项 " + id + " 已" + (on ? "打开" : "关闭")), true);
        return Command.SINGLE_SUCCESS;
    }

    private static int resetOne(CommandContext<CommandSourceStack> ctx) {
        String id = StringArgumentType.getString(ctx, "id");
        YizDiagnostics.reset(id);
        boolean on = YizDiagnostics.isOn(id);
        ctx.getSource().sendSuccess(() -> Component.literal(
            "§a诊断项 " + id + " 已恢复默认（当前" + (on ? "开" : "关") + "）"), true);
        return Command.SINGLE_SUCCESS;
    }
}
