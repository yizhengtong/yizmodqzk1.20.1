package net.minecraft.client.yiz.mixin;

import net.minecraft.client.yiz.itemcfg.FeatureType;
import net.minecraft.client.yiz.itemcfg.ItemConfigGates;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 右键使用/食用 per-player 门控（万能物品配置）。
 * 拦截 {@link ServerPlayerGameMode#useItem}，该玩家关闭 RIGHT_CLICK / FOOD → 交互无效果。
 * 只 mixin vanilla 类，无 @Shadow（生产 SRG refmap 安全）。
 */
@Mixin(ServerPlayerGameMode.class)
public abstract class ItemCfgUseGateMixin {

    @Inject(method = "useItem", at = @At("HEAD"), cancellable = true)
    private void yizmodqzk$gateUseItem(ServerPlayer player, Level level, ItemStack stack, InteractionHand hand,
                                       CallbackInfoReturnable<InteractionResult> cir) {
        if (player.level().isClientSide()) return;
        if (stack.isEmpty()) return;
        if (ItemConfigGates.isDisabled(player, stack, FeatureType.RIGHT_CLICK)) {
            cir.setReturnValue(InteractionResult.PASS);
        } else if (ItemConfigGates.isDisabled(player, stack, FeatureType.FOOD)
                && stack.getItem().isEdible()) {
            cir.setReturnValue(InteractionResult.PASS);
        }
    }
}
