package net.minecraft.client.yiz.mixin;

import net.minecraft.client.yiz.api.PohuanXunzhen;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 破幻寻真：命中前清零无敌帧/受击硬直，命中后对“被拒绝的伤害”做 actuallyHurt 兜底结算。
 *
 * <p>优先级高于普通 mixin，保证在原版无敌帧判断之前执行。</p>
 */
@Mixin(value = LivingEntity.class, priority = 900)
public abstract class PohuanHurtMixin {

    @Inject(method = "hurt", at = @At("HEAD"))
    private void yizmodqzk$pohuanHurtHead(DamageSource source, float amount,
                                         CallbackInfoReturnable<Boolean> cir) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (source.getEntity() instanceof LivingEntity attacker) {
            PohuanXunzhen.onHurtHead(attacker, self);
        }
    }

    @Inject(method = "hurt", at = @At("RETURN"))
    private void yizmodqzk$pohuanHurtReturn(DamageSource source, float amount,
                                           CallbackInfoReturnable<Boolean> cir) {
        LivingEntity self = (LivingEntity) (Object) this;
        try {
            PohuanXunzhen.onHurtReturn(self, source, amount, cir.getReturnValue());
        } finally {
            PohuanXunzhen.endBreach();
        }
    }
}
