package net.minecraft.client.yiz.tool;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.client.yiz.core.asm.AgentLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * /yiz agent — 查询当前是否已动态加载 Agent。
 * <p>Agent 动态加载成功时血量扫描走完整注入路径；未加载时走降级模式（不影响基本血量扫描体系）。</p>
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
        String msg = loaded
            ? "§a✓ Agent 已动态加载（isLoaded = true，血量注入全量生效）"
            : "§c✗ Agent 未动态加载（isLoaded = false，走降级模式）";
        if (source.getEntity() instanceof ServerPlayer player) {
            player.sendSystemMessage(Component.literal(msg));
        } else {
            source.sendSuccess(() -> Component.literal(msg), false);
        }
        return 1;
    }
}
