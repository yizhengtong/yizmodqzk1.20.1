package net.minecraft.client.yiz.mixin;

import net.minecraft.client.yiz.attribute.YizAttributes;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.ShovelItem;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 流体真正破坏 Mixin — 消费 {@link YizAttributes#MINING_ALL} ≥ 1
 * + {@link YizAttributes#MINING_LEVEL} ≥ 10（1.21.1 移植版）。
 *
 * <p>注入 {@code ServerPlayerGameMode#removeBlock} HEAD：
 * 原版 {@code onDestroyedByPlayer} 对流体用 {@code createLegacyBlock()} 重置方块（=原地复活）。
 * 满足条件时直接用空气替换，真正移除流体。</p>
 */
@Mixin(ServerPlayerGameMode.class)
public abstract class ServerDestroyBlockMixin {

    @Shadow protected ServerPlayer player;
    @Shadow protected ServerLevel level;

    @Inject(method = "destroyBlock(Lnet/minecraft/core/BlockPos;)Z",
            at = @At("HEAD"), cancellable = true)
    private void yizmodqzk$removeFluid(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof LiquidBlock)) return;
        if (!holdsMiningTool(player)) return; // 非镐/斧/铲不可破坏流体

        var all = player.getAttribute(YizAttributes.MINING_ALL.get());
        if (all == null || all.getValue() < 1) return;
        var lvl = player.getAttribute(YizAttributes.MINING_LEVEL.get());
        if (lvl == null || lvl.getValue() < 10) return;

        boolean removed = level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
        if (removed) {
            state.getBlock().destroy(level, pos, state);
        }
        cir.setReturnValue(removed);
    }

    private static boolean holdsMiningTool(ServerPlayer player) {
        ItemStack held = player.getMainHandItem();
        return held.getItem() instanceof PickaxeItem
            || held.getItem() instanceof AxeItem
            || held.getItem() instanceof ShovelItem;
    }
}
