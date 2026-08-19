package net.minecraft.client.yiz.mixin;

import net.minecraft.client.yiz.core.ItemStackSizeOverride;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * {@link Slot#getMaxStackSize(ItemStack)} 拦截器 — 让被堆叠数覆盖的物品在容器格子里也堆到覆盖值。
 *
 * <p>原版 {@code Slot.getMaxStackSize(ItemStack)} = {@code min(容器上限, 物品上限)}，容器上限固定 64，
 * 导致堆叠核心改成 99 的物品放进容器格子仍只能堆 64（与背包/光标的 99 不一致）。
 * 对已被 {@link ItemStackSizeOverride} 覆盖的物品，直接返回 {@code stack.getMaxStackSize()}（覆盖值），
 * 绕过容器 64 上限；未覆盖的物品走原逻辑（仍受容器上限约束）。</p>
 */
@Mixin(Slot.class)
public abstract class SlotMaxStackSizeMixin {

    @Inject(method = "getMaxStackSize(Lnet/minecraft/world/item/ItemStack;)I", at = @At("HEAD"), cancellable = true)
    private void yizmodqzk$onGetMaxStackSize(ItemStack stack, CallbackInfoReturnable<Integer> cir) {
        if (stack.isEmpty()) return;
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (ItemStackSizeOverride.isOverridden(id)) {
            cir.setReturnValue(stack.getMaxStackSize());
        }
    }

    /**
     * 无参版本：返回全局堆叠上限（99），避免「空槽位放入」被容器固定 64 截断。
     * 合并到已有物品的槽位走 {@code stack.getMaxStackSize()}（物品上限），不受此影响；
     * 未覆盖的物品（默认 64）仍受物品上限约束，不会因槽位上限放宽而超堆。
     */
    @Inject(method = "getMaxStackSize()I", at = @At("HEAD"), cancellable = true)
    private void yizmodqzk$onGetMaxStackSizeNoArg(CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(ItemStackSizeOverride.MAX);
    }
}
