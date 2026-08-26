package net.minecraft.client.yiz.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.yiz.attribute.YizAttributes;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.ShovelItem;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 流体方块形状 Mixin（仅客户端）— 条件性使水/岩浆可被准星选中（1.21.1 移植版）。
 *
 * <p>条件：挖掘类：全 ≥ 1 + 挖掘等级 ≥ 10 + 手持镐/斧/铲。
 * 不满足任一条件时保持原版 {@link Shapes#empty()}，流体不可选。</p>
 */
@Mixin(LiquidBlock.class)
public abstract class LiquidBlockShapeMixin {

    @Inject(method = "getShape", at = @At("RETURN"), cancellable = true)
    private void yizmodqzk$conditionalShape(BlockState state, BlockGetter level, BlockPos pos,
                                             CollisionContext context, CallbackInfoReturnable<VoxelShape> cir) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        var all = mc.player.getAttribute(YizAttributes.MINING_ALL.get());
        if (all == null || all.getValue() < 1) return;
        var lvl = mc.player.getAttribute(YizAttributes.MINING_LEVEL.get());
        if (lvl == null || lvl.getValue() < 10) return;

        var held = mc.player.getMainHandItem();
        if (held.getItem() instanceof PickaxeItem
            || held.getItem() instanceof AxeItem
            || held.getItem() instanceof ShovelItem) {
            cir.setReturnValue(Shapes.block());
        }
    }
}
