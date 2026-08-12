package net.minecraft.client.yiz.tool;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.client.yiz.core.asm.AgentBridge;
import net.minecraft.client.yiz.core.asm.AgentLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * /yiz agent — 完整诊断 Agent 动态加载状态。
 * <p>区分：attach 成功 ≠ agentmain 执行 ≠ transformer 注册 ≠ 实际注入。
 * 提供各环节状态 + transform 类数 + 最后错误 + 判定，避免误判（如"isLoaded=true 但注入未生效"）。</p>
 * 1.20.1 移植版。
 */
public final class YizAgentCommand {

    private YizAgentCommand() {}

    public static void register() {
        LiteralArgumentBuilder<CommandSourceStack> cmd = Commands.literal("yiz")
            .then(Commands.literal("agent")
                .executes(YizAgentCommand::execute)
            );
        SimpleCommandRegistry.register(cmd);
    }

    private static int execute(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();

        boolean loaded = AgentLoader.isLoaded();
        boolean active = AgentBridge.isAgentActive();
        boolean registered = AgentBridge.isTransformerRegistered();
        int transformCount = AgentBridge.getTransformCount();
        String error = AgentBridge.getLastError();

        StringBuilder sb = new StringBuilder();
        sb.append("§e=== /yiz agent 诊断 ===\n");
        sb.append("§7[加载] attach/loadAgent: ").append(loaded ? "§a成功" : "§c失败").append("\n");
        sb.append("§7[加载] agentmain 已执行（Instrumentation 收到）: ").append(active ? "§a是" : "§c否").append("\n");
        sb.append("§7[注入] transformer 已注册: ").append(registered ? "§a是" : "§c否").append("\n");
        sb.append("§7[注入] 实际 transform 类数: ")
            .append(transformCount > 0 ? "§a" : "§c").append(transformCount).append("\n");
        sb.append("§7[错误] 最后错误: ").append(error == null ? "§a无" : "§c" + error).append("\n");

        if (loaded && active && registered && transformCount > 0) {
            sb.append("§a[判定] Agent 完整生效（已注入 ").append(transformCount).append(" 个类，血量注入全量生效）");
        } else if (loaded && active && registered && transformCount == 0) {
            sb.append("§e[判定] 已注册但尚未注入（transform=0）：实体类可能已加载未重载，"
                + "重新生成/召唤实体后触发注入；或 classloader 隔离导致 transform 未上报");
        } else if (loaded && !active) {
            sb.append("§c[判定] 静默失败：attach 成功但 agentmain 未执行（transformer 未注册）——"
                + "agent jar 入口/加载有问题");
        } else if (!loaded) {
            sb.append("§c[判定] 加载失败，走降级模式（mixin 注入仍生效；agent 级调用点包装/强制tick 失效）");
        } else {
            sb.append("§c[判定] 状态异常，请发日志排查");
        }

        if (source.getEntity() instanceof ServerPlayer player) {
            player.sendSystemMessage(Component.literal(sb.toString()));
        } else {
            source.sendSuccess(() -> Component.literal(sb.toString()), false);
        }
        return 1;
    }
}
