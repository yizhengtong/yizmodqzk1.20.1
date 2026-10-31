package net.minecraft.client.yiz.mixin;

import net.minecraft.client.yiz.api.PohuanXunzhen;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 破幻寻真：本次 hurt 上下文内强制 {@code isInvulnerableTo} 返回 false。
 *
 * <p>注意：1.20.1 的 {@code isInvulnerableTo} 声明在 {@link Entity}（LivingEntity 只是继承），
 * 因此这里 mixin {@link Entity}，再判断实例是否为 LivingEntity；玩家覆写由
 * {@link PohuanPlayerMixin} 处理。</p>
 */
@Mixin(value = Entity.class, priority = 900)
public abstract class PohuanInvulnerableMixin {

    @Inject(method = "isInvulnerableTo", at = @At("RETURN"), cancellable = true)
    private void yizmodqzk$pohuanInvulnerable(DamageSource source, CallbackInfoReturnable<Boolean> cir) {
        if (((Object) this) instanceof LivingEntity living && PohuanXunzhen.isBreaching(living)) {
            cir.setReturnValue(false);
        }
    }
}
