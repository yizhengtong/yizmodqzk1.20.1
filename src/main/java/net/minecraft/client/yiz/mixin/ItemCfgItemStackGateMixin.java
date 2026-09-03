package net.minecraft.client.yiz.mixin;

import net.minecraft.client.yiz.itemcfg.FeatureType;
import net.minecraft.client.yiz.itemcfg.ItemConfigGates;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 放置/对方块右键 + 蓄力释放 + 食用兜底 per-player 门控（万能物品配置）。
 * 全 vanilla 目标（ItemStack），无 @Shadow。
 */
@Mixin(ItemStack.class)
public abstract class ItemCfgItemStackGateMixin {

    /** 放置（BlockItem）或对方块右键（非 BlockItem）→ PASS。下移到 useOn 避免误禁方块交互（开箱/拉杆）。 */
    @Inject(method = "useOn", at = @At("HEAD"), cancellable = true)
    private void yizmodqzk$gateUseOn(UseOnContext context, CallbackInfoReturnable<InteractionResult> cir) {
        ItemStack stack = context.getItemInHand();
        if (stack.isEmpty()) return;
        if (!(context.getPlayer() instanceof ServerPlayer sp)) return;
        if (sp.level().isClientSide()) return;
        FeatureType ft = stack.getItem() instanceof BlockItem ? FeatureType.PLACE : FeatureType.RIGHT_CLICK;
        if (ItemConfigGates.isDisabled(sp, stack, ft)) {
            cir.setReturnValue(InteractionResult.PASS);
        }
    }

    /** 蓄力释放（弓/弩/烟花）→ cancel。持有者经 LivingEntity.useItem 判定。 */
    @Inject(method = "releaseUsing", at = @At("HEAD"), cancellable = true)
    private void yizmodqzk$gateReleaseUsing(Level level, LivingEntity entity, int time, CallbackInfo ci) {
        if (!(entity instanceof ServerPlayer sp)) return;
        if (sp.level().isClientSide()) return;
        ItemStack stack = (ItemStack) (Object) this;
        if (ItemConfigGates.isDisabled(sp, stack, FeatureType.CHARGE_RELEASE)) {
            ci.cancel();
        }
    }

    /** 食用兜底（food 完成时）→ 原样返回不产生效果。 */
    @Inject(method = "finishUsingItem", at = @At("HEAD"), cancellable = true)
    private void yizmodqzk$gateFinishUsing(Level level, LivingEntity entity, CallbackInfoReturnable<ItemStack> cir) {
        if (!(entity instanceof ServerPlayer sp)) return;
        if (sp.level().isClientSide()) return;
        ItemStack stack = (ItemStack) (Object) this;
        if (ItemConfigGates.isDisabled(sp, stack, FeatureType.FOOD)) {
            cir.setReturnValue(stack);
        }
    }
}
