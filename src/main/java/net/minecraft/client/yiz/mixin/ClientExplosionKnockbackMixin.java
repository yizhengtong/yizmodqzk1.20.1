package net.minecraft.client.yiz.mixin;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.yiz.attribute.YizAttributes;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 客户端爆炸包击退也要按 {@link YizAttributes#KNOCKBACK_IMMUNITY} 缩放。
 *
 * <p>服务端 Explosion 的 delta 已经缩放，但 {@code ClientboundExplodePacket} 仍携带
 * 原始击退，客户端 {@code handleExplosion} 会直接加到自己身上；短时间多次爆炸会累积。</p>
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientExplosionKnockbackMixin {

    @Redirect(method = "handleExplosion",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/player/LocalPlayer;setDeltaMovement(Lnet/minecraft/world/phys/Vec3;)V"))
    private void yizmodqzk$clientExplosionKnockback(LocalPlayer player, Vec3 movement) {
        if (net.minecraft.client.yiz.api.KnockbackImmunityRegistry.isImmune(player)) {
            return;
        }
        var inst = player.getAttribute(YizAttributes.KNOCKBACK_IMMUNITY.get());
        if (inst != null && inst.getValue() > 0) {
            double immunity = Math.min(1.0D, inst.getValue());
            if (immunity >= 1.0D) {
                return;
            }
            Vec3 current = player.getDeltaMovement();
            Vec3 knockback = movement.subtract(current);
            player.setDeltaMovement(current.add(knockback.scale(1.0D - immunity)));
            return;
        }
        player.setDeltaMovement(movement);
    }
}
