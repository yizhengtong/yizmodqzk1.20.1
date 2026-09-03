package net.minecraft.client.yiz.mixin;

import net.minecraft.client.yiz.itemcfg.ItemConfigGates;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 语义门控：取消受击（本系统施加）。
 *
 * <p>玩家激活 DAMAGE_CANCEL（adapter 声明且未关闭）→ {@link LivingEntity#hurt} 全部无效。
 * 关闭开关 → 恢复正常受伤。</p>
 */
@Mixin(LivingEntity.class)
public abstract class ItemCfgHurtGateMixin {

    @Inject(method = "hurt", at = @At("HEAD"), cancellable = true)
    private void yizmodqzk$gateHurt(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        if (!((Object) this instanceof ServerPlayer)) return;
        ServerPlayer sp = (ServerPlayer) (Object) this;
        if (sp.level().isClientSide()) return;
        if (ItemConfigGates.hasActiveSemantic(sp, "DAMAGE_CANCEL")) {
            cir.setReturnValue(false);
        }
    }
}
