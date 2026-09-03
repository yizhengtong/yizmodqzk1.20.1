package net.minecraft.client.yiz.mixin;

import net.minecraft.client.yiz.itemcfg.FeatureType;
import net.minecraft.client.yiz.itemcfg.ItemConfigGates;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 背包持续效果 per-player 门控（万能物品配置）。
 *
 * <p>拦截 vanilla {@link ItemStack#inventoryTick}（物品栏每 tick 调 Item.inventoryTick），
 * 该玩家关闭了物品的 INVENTORY_TICK → 跳过 tick，物品在背包里不再产生持续效果。
 * vanilla 目标类，refmap 正常，无需 agent。</p>
 */
@Mixin(ItemStack.class)
public abstract class ItemCfgInventoryTickGateMixin {

    @Inject(method = "inventoryTick", at = @At("HEAD"), cancellable = true)
    private void yizmodqzk$gateInventoryTick(Level level, Entity entity, int slotId, boolean selected, CallbackInfo ci) {
        if (!(entity instanceof ServerPlayer sp)) return;
        if (sp.level().isClientSide()) return;
        ItemStack stack = (ItemStack) (Object) this;
        if (stack.isEmpty()) return;
        if (ItemConfigGates.isDisabled(sp, stack, FeatureType.INVENTORY_TICK)) {
            ci.cancel(); // 背包里不 tick → 持续效果停（per-player）
        }
    }
}
