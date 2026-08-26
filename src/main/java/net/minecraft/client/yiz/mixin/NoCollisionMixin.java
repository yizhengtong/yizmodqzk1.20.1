package net.minecraft.client.yiz.mixin;

import net.minecraft.client.yiz.attribute.YizAttributes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 碰撞免疫 — 属性驱动（no_collision > 0 = 穿过实体）（1.21.1 移植版）。
 */
@Mixin(Entity.class)
public class NoCollisionMixin {

    @Inject(method = "push(Lnet/minecraft/world/entity/Entity;)V", at = @At("HEAD"), cancellable = true)
    private void yizmodqzk$onPush(Entity other, CallbackInfo ci) {
        if (isNoCollision((Entity) (Object) this) || isNoCollision(other)) {
            ci.cancel();
        }
    }

    @Inject(method = "canCollideWith", at = @At("RETURN"), cancellable = true)
    private void yizmodqzk$onCanCollideWith(Entity other, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue()) {
            if (isNoCollision((Entity) (Object) this) || isNoCollision(other)) {
                cir.setReturnValue(false);
            }
        }
    }

    @Inject(method = "isPushable", at = @At("RETURN"), cancellable = true)
    private void yizmodqzk$onIsPushable(CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue() && isNoCollision((Entity) (Object) this)) {
            cir.setReturnValue(false);
        }
    }

    private static boolean isNoCollision(Entity entity) {
        if (!(entity instanceof LivingEntity le)) return false;
        var inst = le.getAttribute(YizAttributes.NO_COLLISION.get());
        if (inst == null) return false;
        return inst.getValue() > 0; // 属性 >0 = 完全无视碰撞
    }
}
