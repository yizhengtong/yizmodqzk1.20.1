package net.minecraft.client.yiz.mixin;

import net.minecraft.client.yiz.api.KnockbackImmunityRegistry;
import net.minecraft.client.yiz.attribute.YizAttributes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 史莱姆方块活塞的弹射也是直接 setDeltaMovement；全套免疫时不再被弹走。
 */
@Mixin(PistonMovingBlockEntity.class)
public abstract class PistonMovingBlockEntityKnockbackMixin {

    @Redirect(method = "moveCollidedEntities",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;setDeltaMovement(DDD)V"))
    private static void yizmodqzk$pistonLaunch(Entity entity, double x, double y, double z) {
        if (!(entity instanceof LivingEntity living)) {
            entity.setDeltaMovement(x, y, z);
            return;
        }
        if (KnockbackImmunityRegistry.isImmune(living)) {
            return;
        }
        var inst = living.getAttribute(YizAttributes.KNOCKBACK_IMMUNITY.get());
        if (inst == null || inst.getValue() <= 0.0D) {
            entity.setDeltaMovement(x, y, z);
            return;
        }
        double factor = 1.0D - Math.min(1.0D, inst.getValue());
        entity.setDeltaMovement(x * factor, y * factor, z * factor);
    }
}
