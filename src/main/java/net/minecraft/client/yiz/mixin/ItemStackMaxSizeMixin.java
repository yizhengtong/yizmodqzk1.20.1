package net.minecraft.client.yiz.mixin;

import net.minecraft.client.yiz.core.ItemStackSizeOverride;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * {@link ItemStack#getMaxStackSize()} 拦截器 — 运行时覆盖物品最大堆叠数。
 *
 * <p>1.20.1 取值链：{@code ItemStack#getMaxStackSize()} →
 * {@code Item#getMaxStackSize(stack)} → 读 {@code Item.Properties#maxStackSize}。
 * 在此注入 HEAD，命中 {@link ItemStackSizeOverride} 的覆盖表则返回自定义值，
 * 否则走原逻辑。对任意已注册物品（原版 + 所有 mod）即时生效。</p>
 *
 * <p>同时连带影响 {@link ItemStack#isStackable()}，因为它内部调用 {@code getMaxStackSize() > 1}。</p>
 */
@Mixin(ItemStack.class)
public abstract class ItemStackMaxSizeMixin {

    @Unique
    private int yizmodqzk$resolvedOverride() {
        ItemStack self = (ItemStack) (Object) this;
        if (self.isEmpty()) return -1; // EMPTY 栈不查表，避免无意义开销
        Item item = self.getItem();
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
        return ItemStackSizeOverride.getOverride(id);
    }

    @Inject(method = "getMaxStackSize", at = @At("HEAD"), cancellable = true)
    private void yizmodqzk$onGetMaxStackSize(CallbackInfoReturnable<Integer> cir) {
        int override = yizmodqzk$resolvedOverride();
        if (override > 0) {
            cir.setReturnValue(override);
        }
    }
}
