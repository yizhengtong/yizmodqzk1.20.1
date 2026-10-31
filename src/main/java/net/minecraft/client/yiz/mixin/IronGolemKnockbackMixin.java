package net.minecraft.client.yiz.mixin;

import net.minecraft.client.yiz.api.KnockbackImmunityRegistry;
import net.minecraft.client.yiz.attribute.YizAttributes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 铁傀儡是特殊的：它不走 {@code LivingEntity#knockback}，而是在
 * {@code IronGolem#doHurtTarget} 里直接：
 * {@code target.setDeltaMovement(target.getDeltaMovement().add(0, 0.4 * (1 - KNOCKBACK_RESISTANCE), 0))}。
 *
 * <p>所以只改 KNOCKBACK_IMMUNITY 的 knockback/Explosion 仍会被铁傀儡短暂击飞；
 * 这里单独按 KNOCKBACK_IMMUNITY 缩放/取消这条垂直速度。</p>
 */
@Mixin(IronGolem.class)
public abstract class IronGolemKnockbackMixin {

    @Redirect(method = "doHurtTarget",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;setDeltaMovement(Lnet/minecraft/world/phys/Vec3;)V"))
    private void yizmodqzk$ironGolemLaunch(Entity entity, Vec3 movement) {
        if (!(entity instanceof LivingEntity living)) {
            entity.setDeltaMovement(movement);
            return;
        }
        if (KnockbackImmunityRegistry.isImmune(living)) {
            return;
        }
        var inst = living.getAttribute(YizAttributes.KNOCKBACK_IMMUNITY.get());
        if (inst == null || inst.getValue() <= 0.0D) {
            entity.setDeltaMovement(movement);
            return;
        }
        double immunity = Math.min(1.0D, inst.getValue());
        Vec3 current = living.getDeltaMovement();
        Vec3 added = movement.subtract(current);
        living.setDeltaMovement(current.add(added.scale(1.0D - immunity)));
    }
}
