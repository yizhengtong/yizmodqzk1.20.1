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
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.client.yiz.tool.health.EntityHealthLocator;

import java.util.Comparator;
import java.util.List;

/**
 * /yiz healthLocate &lt;radius&gt; — 对玩家周围指定半径内所有存活实体强制执行
 * {@link EntityHealthLocator#locate}，输出每类的定位结果（槽 kind/字段/meta）与逻辑血量。
 *
 * <p>纯诊断用途：逐实体验证「真实血量槽是否被识别」（配合涨跌多空攻击测试），
 * 不改任何血量。定位结果同时写入 {@code entity_health_slots.json}。</p>
 */
public final class YizHealthLocateCommand {
    private static final int MAX_ENTITIES = 64;

    private YizHealthLocateCommand() {}

    public static void register() {
        LiteralArgumentBuilder<CommandSourceStack> cmd = Commands.literal("yiz")
                .then(Commands.literal("healthLocate")
                        .then(Commands.argument("radius", DoubleArgumentType.doubleArg(0, 256))
                                .executes(YizHealthLocateCommand::execute)
                        )
                );
        SimpleCommandRegistry.register(cmd);
    }

    private static int execute(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        double radius = DoubleArgumentType.getDouble(ctx, "radius");
        ServerLevel level = source.getLevel();
        Entity origin = source.getEntity();
        if (origin == null) {
            var players = level.players();
            if (players.isEmpty()) {
                source.sendFailure(Component.literal("§c无法确定原点实体"));
                return 0;
            }
            origin = players.get(0);
        }
        AABB box = origin.getBoundingBox().inflate(radius);
        final Entity originEntity = origin;
        List<LivingEntity> entities = level.getEntitiesOfClass(LivingEntity.class, box, e -> e.isAlive() && e.getHealth() > 0);
        entities.sort(Comparator.comparingDouble(e -> e.distanceToSqr(originEntity)));

        int scanned = 0;
        for (LivingEntity e : entities) {
            if (scanned >= MAX_ENTITIES) break;
            scanned++;
            EntityHealthLocator.HealthSlot slot = EntityHealthLocator.locate(e);
            Double logical = EntityHealthLocator.readLocated(e);
            String line;
            if (slot != null) {
                line = String.format("§a%s§r kind=%s field=%s inverse=%s meta=%s 逻辑血=%.1f",
                        e.getClass().getSimpleName(), slot.kind(), slot.fieldName(), slot.inverse(),
                        slot.meta().isEmpty() ? "-" : slot.meta(), logical == null ? -1.0 : logical);
            } else {
                line = String.format("§c%s§r 无槽（负缓存，走 delta/数值兜底）", e.getClass().getSimpleName());
            }
            final String lineFinal = line;
            source.sendSuccess(() -> Component.literal("[EHL] " + lineFinal), false);
        }
        final int scannedFinal = scanned;
        source.sendSuccess(() -> Component.literal("§ehealthLocate 完成：扫描 " + scannedFinal + "/" + entities.size() + " 个实体"), false);
        return scanned;
    }
}
