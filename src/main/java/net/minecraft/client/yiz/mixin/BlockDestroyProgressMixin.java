package net.minecraft.client.yiz.mixin;

import net.minecraft.client.yiz.attribute.YizAttributes;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.ShovelItem;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 无敌方块 + 流体破坏进度 Mixin — 消费 {@link YizAttributes#MINING_LEVEL} ≥ 10
 * + {@link YizAttributes#MINING_ALL} ≥ 1（1.21.1 移植版）。
 *
 * <h3>无敌方块（基岩/屏障等，progress=0）</h3>
 * <p>基础时间 10 秒（200 tick），受挖掘效率加速。</p>
 *
 * <h3>流体方块（水/岩浆等，硬度 100，原版进度极慢）</h3>
 * <p>基础时间 1 秒（20 tick），受挖掘效率加速。</p>
 */
@Mixin(value = BlockBehaviour.BlockStateBase.class)
public abstract class BlockDestroyProgressMixin {

    private static final float EFFECTIVE_HARDNESS = 1.0f;
    /** 无敌方块基准速度：0.15 → 200 tick = 10 秒 */
    private static final float INDESTRUCTIBLE_SPEED = 0.15f;
    /** 流体基准速度：1.5 → 20 tick = 1 秒 */
    private static final float FLUID_SPEED = 1.5f;

    @Inject(method = "getDestroyProgress", at = @At("RETURN"), cancellable = true)
    private void yizmodqzk$overrideProgress(Player player, BlockGetter level, BlockPos pos,
                                             CallbackInfoReturnable<Float> cir) {
        float original = cir.getReturnValue();
        if (player == null) return;

        // 挖掘类：全 ≥ 1 且 挖掘等级 ≥ 10
        var allInst = player.getAttribute(YizAttributes.MINING_ALL.get());
        if (allInst == null || allInst.getValue() < 1) return;
        var lvlInst = player.getAttribute(YizAttributes.MINING_LEVEL.get());
        if (lvlInst == null || lvlInst.getValue() < 10) return;

        if (player.level().getBlockState(pos).isAir()) return;

        boolean isFluid = player.level().getBlockState(pos).getBlock() instanceof LiquidBlock;
        if (isFluid && !holdsMiningTool(player)) return; // 非镐/斧/铲不可破坏流体
        boolean isIndestructible = original <= 0.0f;

        if (!isFluid && !isIndestructible) return; // 正常方块不覆盖

        float speed = isFluid ? FLUID_SPEED : INDESTRUCTIBLE_SPEED;

        var effInst = player.getAttribute(YizAttributes.MINING_EFFICIENCY.get());
        if (effInst != null && effInst.getValue() > 0) {
            speed *= (float) (1.0 + effInst.getValue() / 100.0);
        }

        cir.setReturnValue(speed / EFFECTIVE_HARDNESS / 30.0f);
    }

    private static boolean holdsMiningTool(Player player) {
        ItemStack held = player.getMainHandItem();
        return held.getItem() instanceof PickaxeItem
            || held.getItem() instanceof AxeItem
            || held.getItem() instanceof ShovelItem;
    }
}
