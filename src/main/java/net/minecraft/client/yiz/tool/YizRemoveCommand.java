package net.minecraft.client.yiz.tool;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * /yiz remove &lt;radius&gt; — 清除玩家周围指定半径内的所有非玩家实体。
 * 1.20.1 移植版。
 */
public final class YizRemoveCommand {
    private static final Logger LOGGER = LoggerFactory.getLogger("YizRemoveCmd");

    private YizRemoveCommand() {}

    public static void register() {
        LiteralArgumentBuilder<CommandSourceStack> cmd = Commands.literal("yiz")
                .then(Commands.literal("remove")
                        .then(Commands.argument("radius", DoubleArgumentType.doubleArg(0, 256))
                                .executes(YizRemoveCommand::execute)
                        )
                );

        SimpleCommandRegistry.register(cmd);
    }

    private static int execute(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        double radius = DoubleArgumentType.getDouble(ctx, "radius");

        if (!(source.getEntity() instanceof Player player)) {
            source.sendFailure(Component.literal("§c此指令只能由玩家执行"));
            return 0;
        }

        ServerLevel level = (ServerLevel) player.level();
        AABB box = player.getBoundingBox().inflate(radius);

        List<Entity> snapshot = new ArrayList<>(
                level.getEntitiesOfClass(Entity.class, box, e -> !(e instanceof Player))
        );

        int count = 0;
        for (Entity entity : snapshot) {
            if (entity.isRemoved()) continue;
            int id = entity.getId();
            // /yiz remove 后门：临时放行本模组免移除拦截，允许真正清除本模组实体
            net.minecraft.client.yiz.tool.health.EntityASMUtil.beginForceRemove(id);
            boolean ok;
            try {
                ok = EntityRemovalUtil.forceRemove(entity);
            } finally {
                net.minecraft.client.yiz.tool.health.EntityASMUtil.endForceRemove(id);
            }
            if (ok) {
                // /yiz remove 后门：清除本模组实体后从不死注册表/保护 id 集合中摘除，防止守卫复活
                try {
                    Class<?> yizxian = Class.forName("net.minecraft.client.yiz.xian.entity.base.YizxianMob");
                    yizxian.getMethod("forceRemoveCleanup", Entity.class).invoke(null, entity);
                } catch (Throwable ignored) {}
                count++;
            }
        }

        final int removed = count;
        source.sendSuccess(() -> Component.literal(
                "§a已清除 " + removed + " 个实体（半径 " + radius + " 格）"), true);
        LOGGER.info("{} removed {} entities within radius {}",
                source.getDisplayName(), removed, radius);

        return Command.SINGLE_SUCCESS;
    }
}
