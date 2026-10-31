package net.minecraft.client.yiz.mixin;

import net.minecraft.client.yiz.api.KnockbackImmunityRegistry;
import net.minecraft.client.yiz.attribute.YizAttributes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 钓鱼竿拉拽也是直接 setDeltaMovement，绕过 knockback；这里按免疫值缩放/取消。
 */
@Mixin(FishingHook.class)
public abstract class FishingHookKnockbackMixin {

    @Redirect(method = "pullEntity",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;setDeltaMovement(Lnet/minecraft/world/phys/Vec3;)V"))
    private void yizmodqzk$pullEntity(Entity entity, Vec3 movement) {
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
