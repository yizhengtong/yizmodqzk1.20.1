package net.minecraft.client.yiz.mixin;

import net.minecraft.client.yiz.attribute.YizAttributes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

/**
 * 让 {@link YizAttributes#KNOCKBACK_IMMUNITY} 也覆盖爆炸击退。
 *
 * <p>1.20.1 的 {@code Explosion#explode} 不调用 {@code LivingEntity#knockback}，而是直接
 * {@code entity.setDeltaMovement(entity.getDeltaMovement().add(vec))}，所以只改
 * {@code knockback} 是不够的。</p>
 */
@Mixin(Explosion.class)
public abstract class ExplosionKnockbackMixin {

    @Shadow
    @Final
    private Map<Player, Vec3> hitPlayers;

    /**
     * ServerLevel 发 ClientboundExplodePacket 时用的是 hitPlayers 里的原始 vec，
     * 只改 setDeltaMovement 不会改包。这里在 explode TAIL 把包击退也按免疫缩放。
     */
    @Inject(method = "explode", at = @At("TAIL"))
    private void yizmodqzk$scaleExplosionPacket(CallbackInfo ci) {
        for (Map.Entry<Player, Vec3> entry : this.hitPlayers.entrySet()) {
            Player player = entry.getKey();
            if (net.minecraft.client.yiz.api.KnockbackImmunityRegistry.isImmune(player)) {
                entry.setValue(Vec3.ZERO);
                continue;
            }
            var inst = player.getAttribute(YizAttributes.KNOCKBACK_IMMUNITY.get());
            if (inst == null || inst.getValue() <= 0.0D) {
                continue;
            }
            double immunity = Math.min(1.0D, inst.getValue());
            entry.setValue(entry.getValue().scale(Math.max(0.0D, 1.0D - immunity)));
        }
    }

    @Redirect(method = "explode",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;setDeltaMovement(Lnet/minecraft/world/phys/Vec3;)V"))
    private void yizmodqzk$explosionKnockback(Entity entity, Vec3 movement) {
        if (entity instanceof LivingEntity living) {
            if (net.minecraft.client.yiz.api.KnockbackImmunityRegistry.isImmune(living)) {
                return;
            }
            var inst = living.getAttribute(YizAttributes.KNOCKBACK_IMMUNITY.get());
            if (inst != null && inst.getValue() > 0) {
                double immunity = Math.min(1.0D, inst.getValue());
                if (immunity >= 1.0D) {
                    return;
                }
                entity.setDeltaMovement(movement.scale(1.0D - immunity));
                return;
            }
        }
        entity.setDeltaMovement(movement);
    }
}
