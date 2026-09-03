package net.minecraft.client.yiz.mixin;

import net.minecraft.client.yiz.itemcfg.ConfigRegistry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 持有类适配库：不死图腾免死（挂在通用 HELD_EFFECTS 开关下）。
 *
 * <p>玩家关闭了手持图腾的通用"持有/背包效果"（HELD_EFFECTS）→
 * {@link LivingEntity#checkTotemDeathProtection} 返回 false → 图腾不再免死（正常死亡）。</p>
 *
 * <p>不死图腾的免死是 vanilla 引擎特殊处理（不覆写 Item 方法，反射识别不了），
 * 属适配库的 vanilla 已知效果拦截；第三方 mod 的持有类效果靠 adapter 声明。</p>
 */
@Mixin(LivingEntity.class)
public abstract class ItemCfgTotemGateMixin {

    @Inject(method = "checkTotemDeathProtection", at = @At("HEAD"), cancellable = true)
    private void yizmodqzk$gateTotem(DamageSource source, CallbackInfoReturnable<Boolean> cir) {
        if (!((Object) this instanceof ServerPlayer)) return;
        ServerPlayer sp = (ServerPlayer) (Object) this;
        if (sp.level().isClientSide()) return;
        if (isTotemDisabled(sp, sp.getMainHandItem()) || isTotemDisabled(sp, sp.getOffhandItem())) {
            cir.setReturnValue(false);
        }
    }

    private static boolean isTotemDisabled(ServerPlayer sp, ItemStack stack) {
        if (stack.isEmpty() || !stack.is(Items.TOTEM_OF_UNDYING)) return false;
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return id != null && ConfigRegistry.isDisabled(sp.getUUID(), id, "HELD_EFFECTS");
    }
}
