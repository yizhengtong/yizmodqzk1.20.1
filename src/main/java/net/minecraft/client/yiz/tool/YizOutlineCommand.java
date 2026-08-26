package net.minecraft.client.yiz.tool;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.client.yiz.api.OutlineMarker;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * /yiz mb &lt;0-5&gt; — 给主手物品添加描边效果，等级 0-5（白/彩虹/红/紫/蓝/绿）。
 * /yiz mb off — 移除主手物品描边。
 */
public final class YizOutlineCommand {

    private YizOutlineCommand() {}

    public static void register() {
        LiteralArgumentBuilder<CommandSourceStack> cmd = Commands.literal("yiz")
                .then(Commands.literal("mb")
                        .then(Commands.argument("level", IntegerArgumentType.integer(0, 5))
                                .executes(YizOutlineCommand::apply))
                        .then(Commands.literal("off").executes(YizOutlineCommand::clear)));
        SimpleCommandRegistry.register(cmd);
    }

    private static int apply(CommandContext<CommandSourceStack> ctx) {
        int level = IntegerArgumentType.getInteger(ctx, "level");
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer player)) {
            ctx.getSource().sendFailure(Component.literal("§c此指令只能由玩家执行"));
            return 0;
        }
        ItemStack stack = player.getMainHandItem();
        if (stack.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("§c主手没有物品"));
            return 0;
        }
        OutlineMarker.setLevel(stack, level);
        ctx.getSource().sendSuccess(() -> Component.literal(
                "§a已给主手物品添加描边（等级 " + level + "）"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int clear(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer player)) {
            ctx.getSource().sendFailure(Component.literal("§c此指令只能由玩家执行"));
            return 0;
        }
        ItemStack stack = player.getMainHandItem();
        if (stack.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("§c主手没有物品"));
            return 0;
        }
        OutlineMarker.clear(stack);
        ctx.getSource().sendSuccess(() -> Component.literal("§a已移除主手物品描边"), false);
        return Command.SINGLE_SUCCESS;
    }
}
