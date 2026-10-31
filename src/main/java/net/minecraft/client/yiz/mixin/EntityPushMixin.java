package net.minecraft.client.yiz.mixin;

import net.minecraft.client.yiz.attribute.YizAttributes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 实体碰撞/挤压产生的位移也受 {@link YizAttributes#KNOCKBACK_IMMUNITY} 影响。
 *
 * <p>1.20.1 的 {@code Entity#push(double,double,double)} 是所有碰撞推挤的最终入口，
 * 这里按免疫值缩放；1.0 时完全取消。这样极尽四件套不会被生物/玩家挤开。</p>
 */
@Mixin(Entity.class)
public abstract class EntityPushMixin {

    @Inject(method = "push(DDD)V", at = @At("HEAD"), cancellable = true)
    private void yizmodqzk$onPush(double x, double y, double z, CallbackInfo ci) {
        if (!((Object) this instanceof LivingEntity living)) {
            return;
        }
        if (net.minecraft.client.yiz.api.KnockbackImmunityRegistry.isImmune(living)) {
            ci.cancel();
            return;
        }
        var inst = living.getAttribute(YizAttributes.KNOCKBACK_IMMUNITY.get());
        if (inst == null || inst.getValue() <= 0.0D) {
            return;
        }

        double immunity = Math.min(1.0D, inst.getValue());
        if (immunity >= 1.0D) {
            ci.cancel();
            return;
        }

        double factor = 1.0D - immunity;
        living.setDeltaMovement(living.getDeltaMovement().add(x * factor, y * factor, z * factor));
        living.hasImpulse = true;
        ci.cancel();
    }
}
