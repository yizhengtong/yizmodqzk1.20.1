package net.minecraft.client.yiz.tool;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

/**
 * /yiz creature — 生物组件配置的运行期操作。
 *
 * <p>子指令：</p>
 * <ul>
 *   <li>{@code /yiz creature refresh}：让当前世界中带组件配置的实体重新应用一次原型属性
 *       （数据包改完配置后不必重新召唤；血量按最大生命比例保留）。</li>
 * </ul>
 */
public final class YizCreatureCommand {

    private YizCreatureCommand() {}

    public static void register() {
        LiteralArgumentBuilder<CommandSourceStack> cmd = Commands.literal("yiz")
            .then(Commands.literal("creature")
                .then(Commands.literal("refresh").executes(YizCreatureCommand::refresh)));
        SimpleCommandRegistry.register(cmd);
    }

    private static int refresh(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        int count = net.minecraft.client.yiz.creature.CreatureComponentRefresher.refreshAll(server);
        ctx.getSource().sendSuccess(() -> Component.literal(
            "§a已刷新 " + count + " 个带有组件配置的实体"), true);
        return Command.SINGLE_SUCCESS;
    }
}
