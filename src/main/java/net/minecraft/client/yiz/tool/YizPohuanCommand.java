package net.minecraft.client.yiz.tool;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.client.yiz.api.PohuanXunzhen;
import net.minecraft.client.yiz.attribute.YizAttributes;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.player.Player;

/**
 * /yiz pohuan &lt;value&gt; — 设置自己的【破幻寻真】基础值（0~100）。
 * /yiz pohuan query — 查询当前值/是否生效。
 *
 * <p>总值 &gt; 1 生效；用于测试/发放，避免必须依赖物品属性编辑器。</p>
 */
public final class YizPohuanCommand {

    private YizPohuanCommand() {}

    public static void register() {
        LiteralArgumentBuilder<CommandSourceStack> cmd = Commands.literal("yiz")
            .then(Commands.literal("pohuan")
                .then(Commands.literal("query").executes(YizPohuanCommand::query))
                .then(Commands.argument("value", DoubleArgumentType.doubleArg(0, 100))
                    .executes(YizPohuanCommand::set)));
        SimpleCommandRegistry.register(cmd);
    }

    private static int set(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        double v = DoubleArgumentType.getDouble(ctx, "value");
        if (!Double.isFinite(v)) {
            src.sendFailure(Component.literal("§c数值必须有限"));
            return 0;
        }
        try {
            Player p = src.getPlayerOrException();
            AttributeInstance inst = p.getAttribute(YizAttributes.POHUAN_XUNZHEN.get());
            if (inst == null) {
                src.sendFailure(Component.literal("§c玩家未挂载破幻寻真属性（属性注册失败？）"));
                return 0;
            }
            inst.setBaseValue(v);
            src.sendSuccess(() -> Component.literal("§a破幻寻真 = " + inst.getValue()
                + (PohuanXunzhen.isActive(p) ? " §7(已生效 >1)" : " §7(未生效：需 >1)")), true);
            return 1;
        } catch (Throwable t) {
            src.sendFailure(Component.literal("§c设置失败: " + t));
            return 0;
        }
    }

    private static int query(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        try {
            Player p = src.getPlayerOrException();
            AttributeInstance inst = p.getAttribute(YizAttributes.POHUAN_XUNZHEN.get());
            if (inst == null) {
                src.sendFailure(Component.literal("§c玩家未挂载破幻寻真属性"));
                return 0;
            }
            src.sendSuccess(() -> Component.literal("§e破幻寻真: base=" + inst.getBaseValue()
                + " total=" + inst.getValue()
                + " active=" + PohuanXunzhen.isActive(p)), false);
            return 1;
        } catch (Throwable t) {
            src.sendFailure(Component.literal("§c查询失败: " + t));
            return 0;
        }
    }
}
