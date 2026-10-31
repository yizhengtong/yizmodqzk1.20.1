package net.minecraft.client.yiz.mixin;

import net.minecraft.client.yiz.api.PohuanXunzhen;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 破幻寻真：玩家目标的 {@code isInvulnerableTo} 覆写（创造式无敌走 abilities）同样破除。
 *
 * <p>创造/旁观玩家由 {@link PohuanXunzhen#canAffect} 拦住，不会进入破除上下文；
 * 这里只对“生存/冒险玩家被套创造无敌”生效。</p>
 */
@Mixin(value = Player.class, priority = 900)
public abstract class PohuanPlayerMixin {

    @Inject(method = "isInvulnerableTo", at = @At("RETURN"), cancellable = true)
    private void yizmodqzk$pohuanPlayerInvulnerable(DamageSource source, CallbackInfoReturnable<Boolean> cir) {
        Player self = (Player) (Object) this;
        if (PohuanXunzhen.isBreaching(self)) {
            cir.setReturnValue(false);
        }
    }
}
