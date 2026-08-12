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
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * /yiz setHealth &lt;radius&gt; &lt;value&gt; — 将玩家周围指定半径内所有实体的血量设置为指定数值。
 *
 * <p><b>数值不做正负限制</b>：可为负（负血量会判定死亡）、零（死亡）、正数（治疗/加血）、
 * 超大值（超上限血量，float 溢出为 +Infinity 亦允许）。绕过 vanilla {@code setHealth} 的
 * [0, maxHealth] clamp 与自研实体的防御 clamp，按实体类型走最底层直写路径：</p>
 * <ul>
 *   <li>混淆血量实体（YizxianMob）→ {@code SecureHealthClosure.setHealthUnbounded}（权威表+混淆串，无 clamp）</li>
 *   <li>定位到真实血量槽 → {@code EntityHealthLocator.writeLocated}（字段/通道直写）</li>
 *   <li>兜底 → {@code DirectHealthFallback.setFloatChannelValue}（vanilla 血量 DataItem 直写）</li>
 * </ul>
 */
public final class YizSetHealthCommand {
    private static final Logger LOGGER = LoggerFactory.getLogger("YizSetHealthCmd");

    private YizSetHealthCommand() {}

    public static void register() {
        LiteralArgumentBuilder<CommandSourceStack> cmd = Commands.literal("yiz")
                .then(Commands.literal("setHealth")
                        .then(Commands.argument("radius", DoubleArgumentType.doubleArg(0, 256))
                                .then(Commands.argument("value", DoubleArgumentType.doubleArg())
                                        .executes(YizSetHealthCommand::execute)
                                )
                        )
                );

        SimpleCommandRegistry.register(cmd);
    }

    private static int execute(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        double radius = DoubleArgumentType.getDouble(ctx, "radius");
        double value = DoubleArgumentType.getDouble(ctx, "value");

        if (Double.isNaN(value)) {
            source.sendFailure(Component.literal("§c数值不能为 NaN"));
            return 0;
        }
        float target = (float) value;

        if (!(source.getEntity() instanceof Player player)) {
            source.sendFailure(Component.literal("§c此指令只能由玩家执行"));
            return 0;
        }

        ServerLevel level = (ServerLevel) player.level();
        AABB box = player.getBoundingBox().inflate(radius);

        // 排除命令使用者自己（/yiz setHealth 不改使用者血量），其余存活实体全部包含（含其他玩家）
        List<Entity> snapshot = new ArrayList<>(
                level.getEntitiesOfClass(Entity.class, box, e -> !e.isRemoved() && e != player)
        );

        int count = 0;
        for (Entity entity : snapshot) {
            if (entity instanceof LivingEntity living && setEntityHealth(living, target)) {
                count++;
            }
        }

        final int done = count;
        source.sendSuccess(() -> Component.literal(
                "§a已将 " + done + " 个实体血量设置为 " + target + "（半径 " + radius + " 格，不限正负）"), true);
        LOGGER.info("{} setHealth {} within radius {} -> {} entities",
                source.getDisplayName(), target, radius, done);

        return Command.SINGLE_SUCCESS;
    }

    /** 无正负限制设置实体血量（按实体类型走最底层直写路径，绕过一切 clamp）。 */
    private static boolean setEntityHealth(LivingEntity entity, float value) {
        try {
            // 1. 混淆血量实体（YizxianMob）：权威表 + 混淆串（无 clamp 版本）
            if (net.minecraft.client.yiz.tool.health.SecureHealthClosure.hasObf(entity)) {
                net.minecraft.client.yiz.tool.health.SecureHealthClosure.setHealthUnbounded(entity, value);
                return true;
            }
            // 2. 定位真实血量槽直写（绕过 setHealth clamp；字段/DataParameter 均无 clamp）
            if (net.minecraft.client.yiz.tool.health.EntityHealthLocator.locate(entity) != null) {
                net.minecraft.client.yiz.tool.health.EntityHealthLocator.writeLocated(entity, value);
                return true;
            }
            // 3. 兜底：直写 vanilla 血量 DataItem（绕过 set() 限伤/clamp）
            return net.minecraft.client.yiz.tool.health.DirectHealthFallback.setFloatChannelValue(
                    entity,
                    net.minecraft.client.yiz.tool.health.DirectHealthFallback.VANILLA_HEALTH_ACCESSOR,
                    value, true);
        } catch (Throwable t) {
            LOGGER.warn("setEntityHealth failed for {}: {}", entity, t.toString());
            return false;
        }
    }
}
