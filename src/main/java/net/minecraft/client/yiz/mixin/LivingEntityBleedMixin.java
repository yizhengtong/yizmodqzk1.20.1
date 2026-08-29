package net.minecraft.client.yiz.mixin;

import net.minecraft.client.yiz.tool.health.BleedSystem;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 流血系统接入：
 * - {@code baseTick} TAIL：每 tick 递减流血状态。
 * - {@code hurt} HEAD/RETURN：记录受伤前后血量差，目标被外部伤害实际扣血 → 触发流血反应
 *   （按 {@code 扣血量 × 比例} 造成通用伤害，流血伤害自身不连锁）。
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityBleedMixin {

    private static final ThreadLocal<Float> BLEED_BEFORE = new ThreadLocal<>();

    /** 生产 SRG 环境下 @Shadow 方法名解析会因 refmap 不命中而失败，改用强转调用
     *  （reobf 阶段把 LivingEntity.getHealth() 引用重映射到 m_21223_，无需运行时解析）。 */
    private float yizqzk$health() {
        return ((LivingEntity) (Object) this).getHealth();
    }

    @Inject(method = "baseTick", at = @At("TAIL"))
    private void yizmodqzk$bleedTick(CallbackInfo ci) {
        BleedSystem.tick((LivingEntity) (Object) this);
    }

    @Inject(method = "hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z", at = @At("HEAD"))
    private void yizmodqzk$bleedBefore(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        BLEED_BEFORE.set(yizqzk$health());
    }

    @Inject(method = "hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z", at = @At("RETURN"))
    private void yizmodqzk$bleedAfter(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        Float before = BLEED_BEFORE.get();
        BLEED_BEFORE.remove();
        if (before == null) return;
        float delta = before - yizqzk$health();
        if (delta > 0.001f) {
            BleedSystem.onExternalDamage((LivingEntity) (Object) this, delta);
        }
    }
}
