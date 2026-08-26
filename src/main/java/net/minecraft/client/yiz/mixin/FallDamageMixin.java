package net.minecraft.client.yiz.mixin;

import net.minecraft.client.yiz.attribute.YizAttributes;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 摔伤减免 Mixin — 消费 {@link YizAttributes#FALL_SAFE} 与 {@link YizAttributes#FALL_REDUCE}
 * （1.21.1 移植版）。
 *
 * <p>在 {@code LivingEntity#calculateFallDamage} 返回值上应用：
 * <pre>dmg = max(0, dmg - FALL_SAFE)         // 固定安全距离减免（格→点）
 *dmg = dmg × (1 - FALL_REDUCE / 100)    // 百分比减免</pre>
 * 先固定后百分比。</p>
 *
 * <p>注意：这与技能授予的 {@code FallImmunityTracker}（一次性完全免疫）不同——
 * 后者在 hurt 层直接 return 0，本 Mixin 是属性驱动的数值减免。</p>
 */
@Mixin(LivingEntity.class)
public abstract class FallDamageMixin {

    @Inject(method = "calculateFallDamage", at = @At("RETURN"), cancellable = true)
    private void yizmodqzk$modifyFallDamage(float fallDistance, float damageMultiplier,
                                            CallbackInfoReturnable<Integer> cir) {
        LivingEntity self = (LivingEntity) (Object) this;
        int dmg = cir.getReturnValue();
        if (dmg <= 0) return;

        // 固定安全距离减免
        var safeInst = self.getAttribute(YizAttributes.FALL_SAFE.get());
        if (safeInst != null) {
            double safe = safeInst.getValue();
            if (safe > 0) dmg = Math.max(0, dmg - (int) safe);
        }
        // 百分比减免
        var reduceInst = self.getAttribute(YizAttributes.FALL_REDUCE.get());
        if (reduceInst != null) {
            double reduce = reduceInst.getValue();
            if (reduce > 0) dmg = (int) (dmg * (1.0 - Math.min(1.0, reduce / 100.0)));
        }

        cir.setReturnValue(dmg);
    }
}
