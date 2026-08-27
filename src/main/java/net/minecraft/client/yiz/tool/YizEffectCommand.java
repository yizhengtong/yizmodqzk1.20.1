package net.minecraft.client.yiz.tool;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import java.util.UUID;

/**
 * /yiz eff — 每实例效果开关（隔离模型测试/调试）。
 *
 * <p>子指令：</p>
 * <ul>
 *   <li>{@code /yiz eff owner &lt;target&gt; &lt;player&gt;}：设实体归属玩家（自走棋招聘绑定）。</li>
 *   <li>{@code /yiz eff set &lt;target&gt; &lt;effect&gt; on|off}：对该实例显式开关某效果（覆盖类型基础）。</li>
 *   <li>{@code /yiz eff reset &lt;target&gt;}：清除显式覆盖，恢复类型基础形态。</li>
 * </ul>
 */
public final class YizEffectCommand {

    private static final String[] KNOWN_EFFECTS = {
        net.minecraft.client.yiz.tool.effect.InstanceEffectState.REMOVE_IMMUNITY,
        net.minecraft.client.yiz.tool.effect.InstanceEffectState.TELEPORT_IMMUNITY,
        net.minecraft.client.yiz.tool.effect.InstanceEffectState.POTION_IMMUNITY,
        net.minecraft.client.yiz.tool.effect.InstanceEffectState.KNOCKBACK_IMMUNITY,
        net.minecraft.client.yiz.tool.effect.InstanceEffectState.PHYSICAL_IMMUNITY,
        net.minecraft.client.yiz.tool.effect.InstanceEffectState.RIDE_IMMUNITY,
    };

    private YizEffectCommand() {}

    public static void register() {
        LiteralArgumentBuilder<CommandSourceStack> cmd = Commands.literal("yiz")
            .then(Commands.literal("eff")
                .then(Commands.literal("owner")
                    .then(Commands.argument("target", EntityArgument.entity())
                        .then(Commands.argument("player", EntityArgument.player())
                            .executes(YizEffectCommand::setOwner))))
                .then(Commands.literal("set")
                    .then(Commands.argument("target", EntityArgument.entity())
                        .then(Commands.argument("effect", StringArgumentType.word())
                            .then(Commands.literal("on").executes(ctx -> setEffect(ctx, true)))
                            .then(Commands.literal("off").executes(ctx -> setEffect(ctx, false))))))
                .then(Commands.literal("reset")
                    .then(Commands.argument("target", EntityArgument.entity())
                        .executes(YizEffectCommand::reset))));
        SimpleCommandRegistry.register(cmd);
    }

    private static int setOwner(CommandContext<CommandSourceStack> ctx) {
        try {
            Entity target = EntityArgument.getEntity(ctx, "target");
            Player owner = EntityArgument.getPlayer(ctx, "player");
            if (!(target instanceof LivingEntity living)) {
                ctx.getSource().sendFailure(Component.literal("§c目标必须是存活实体"));
                return 0;
            }
            net.minecraft.client.yiz.tool.effect.InstanceEffectState.setOwner(living, owner.getUUID());
            ctx.getSource().sendSuccess(() -> Component.literal(
                "§a已设归属: " + living.getName().getString() + " → " + owner.getName().getString()), true);
            return Command.SINGLE_SUCCESS;
        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("§c" + e.getMessage()));
            return 0;
        }
    }

    private static int setEffect(CommandContext<CommandSourceStack> ctx, boolean on) {
        try {
            Entity target = EntityArgument.getEntity(ctx, "target");
            String effect = StringArgumentType.getString(ctx, "effect");
            if (!isKnown(effect)) {
                ctx.getSource().sendFailure(Component.literal("§c未知效果: " + effect
                    + "（可选 " + String.join("/", KNOWN_EFFECTS) + "）"));
                return 0;
            }
            if (!(target instanceof LivingEntity living)) {
                ctx.getSource().sendFailure(Component.literal("§c目标必须是存活实体"));
                return 0;
            }
            UUID op = ctx.getSource().getEntity() instanceof Player p ? p.getUUID() : null;
            boolean ok = net.minecraft.client.yiz.tool.effect.InstanceEffectState.setEffect(living, op, effect, on);
            if (!ok) {
                ctx.getSource().sendFailure(Component.literal("§c无权修改（非归属玩家或调用栈不被信任）"));
                return 0;
            }
            boolean now = net.minecraft.client.yiz.tool.effect.InstanceEffectState.isEffectEnabled(living, effect);
            ctx.getSource().sendSuccess(() -> Component.literal(
                "§a" + living.getName().getString() + " " + effect + " → " + (on ? "§a开启" : "§7关闭")
                    + "（当前生效=" + now + "）"), true);
            return Command.SINGLE_SUCCESS;
        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("§c" + e.getMessage()));
            return 0;
        }
    }

    private static int reset(CommandContext<CommandSourceStack> ctx) {
        try {
            Entity target = EntityArgument.getEntity(ctx, "target");
            if (!(target instanceof LivingEntity living)) {
                ctx.getSource().sendFailure(Component.literal("§c目标必须是存活实体"));
                return 0;
            }
            net.minecraft.client.yiz.tool.effect.InstanceEffectState.resetToBase(living);
            ctx.getSource().sendSuccess(() -> Component.literal(
                "§a" + living.getName().getString() + " 已恢复基础形态"), true);
            return Command.SINGLE_SUCCESS;
        } catch (Exception e) {
            ctx.getSource().sendFailure(Component.literal("§c" + e.getMessage()));
            return 0;
        }
    }

    private static boolean isKnown(String effect) {
        for (String k : KNOWN_EFFECTS) {
            if (k.equals(effect)) return true;
        }
        return false;
    }
}
