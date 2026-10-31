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

    /** 每个目标类只打印一次“跳过自定义 hurt”。 */
    private static final java.util.Set<String> yizmodqzk$SKIP_LOGGED =
        java.util.concurrent.ConcurrentHashMap.newKeySet();

    @Inject(method = "hurt", at = @At("HEAD"), cancellable = true)
    private void yizmodqzk$pohuanHurtHead(DamageSource source, float amount,
                                         CallbackInfoReturnable<Boolean> cir) {
        // 正在执行“基类 hurt 结算”：直接走原方法体，不再递归
        if (PohuanXunzhen.isInBaseHurt()) return;
        LivingEntity self = (LivingEntity) (Object) this;
        if (source.getEntity() instanceof LivingEntity attacker) {
            boolean breached = PohuanXunzhen.onHurtHead(attacker, self);
            // hurt 内用虚拟方法/状态机限伤（DamageCap()/RangeLimit()/damageBucket）时，
            // 跳过子类覆写，改调基类 LivingEntity.hurt（保留伤害修正/暴击/穿甲）。
            if (breached && PohuanXunzhen.shouldSkipHurt(self)) {
                if (yizmodqzk$SKIP_LOGGED.add(self.getClass().getName())) {
                    net.minecraft.client.yiz.tizMod.LOGGER.info(
                        "[破幻寻真] 跳过自定义 hurt：{} amount={} → 基类 LivingEntity.hurt",
                        self.getClass().getName(), amount);
                }
                boolean result;
                try {
                    result = PohuanXunzhen.runBaseHurt(self, source, amount);
                } finally {
                    PohuanXunzhen.endBreach();
                }
                cir.setReturnValue(result);
            }
        }
    }

    @Inject(method = "hurt", at = @At("RETURN"), cancellable = true)
    private void yizmodqzk$pohuanHurtReturn(DamageSource source, float amount,
                                           CallbackInfoReturnable<Boolean> cir) {
        // 基类 hurt 的 RETURN 由外层 force_base_hurt 调用方处理，避免提前清上下文
        if (PohuanXunzhen.isInBaseHurt()) return;
        LivingEntity self = (LivingEntity) (Object) this;
        try {
            PohuanXunzhen.onHurtReturn(self, source, amount, cir.getReturnValue());
        } finally {
            PohuanXunzhen.endBreach();
        }
    }
}
