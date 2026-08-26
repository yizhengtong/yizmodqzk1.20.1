package net.minecraft.client.yiz.mixin;

import net.minecraft.client.yiz.attribute.YizAttributes;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import javax.annotation.Nullable;

/**
 * 无敌方块掉落 Mixin — 消费 {@link YizAttributes#MINING_LEVEL} ≥ 10
 * + {@link YizAttributes#MINING_ALL} ≥ 1（1.21.1 移植版）。
 *
 * <p>注入 {@link Block#playerDestroy} HEAD：对基岩、屏障、水源、岩浆、
 * 末地传送门等常规不掉落的无敌方块，满足条件时额外生成方块物品掉落。</p>
 */
@Mixin(Block.class)
public abstract class BlockPlayerDestroyMixin {

    @Inject(method = "playerDestroy", at = @At("HEAD"))
    private void yizmodqzk$dropIndestructibleItem(Level level, Player player, BlockPos pos,
                                                   BlockState state, @Nullable BlockEntity blockEntity,
                                                   ItemStack tool, CallbackInfo ci) {
        if (level.isClientSide()) return;
        if (player == null || player.isCreative()) return;

        var lvlInst = player.getAttribute(YizAttributes.MINING_LEVEL.get());
        if (lvlInst == null || lvlInst.getValue() < 10) return;
        var allInst = player.getAttribute(YizAttributes.MINING_ALL.get());
        if (allInst == null || allInst.getValue() < 1) return;

        if (!isNormallyIndestructible(state)) return;

        // 直接掉落方块物品本身
        Block block = state.getBlock();
        ItemStack drop = new ItemStack(block.asItem());
        if (drop.isEmpty()) return;

        Block.popResource(level, pos, drop);
    }

    /** 判断方块是否常规无敌（硬度为负或为流体） */
    private static boolean isNormallyIndestructible(BlockState state) {
        Block block = state.getBlock();
        return block instanceof LiquidBlock
            || block == Blocks.BEDROCK
            || block == Blocks.BARRIER
            || block == Blocks.END_PORTAL_FRAME
            || block == Blocks.COMMAND_BLOCK
            || block == Blocks.CHAIN_COMMAND_BLOCK
            || block == Blocks.REPEATING_COMMAND_BLOCK
            || block == Blocks.STRUCTURE_BLOCK
            || block == Blocks.END_GATEWAY
            || block == Blocks.NETHER_PORTAL
            || block == Blocks.END_PORTAL;
    }
}
