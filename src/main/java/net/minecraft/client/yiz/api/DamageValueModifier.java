package net.minecraft.client.yiz.api;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;

/**
 * 伤害数值修改器接口
 * <p>
 * 用于在 {@code LivingEntity.hurt()} 入口处修改伤害数值，
 * 由 {@link DamageValueModifierRegistry} 管理。
 * </p>
 */
@FunctionalInterface
public interface DamageValueModifier {
    /**
     * @param target  承受伤害的实体
     * @param source  伤害来源
     * @param amount  进入 hurt() 时的原始伤害数值
     * @return 修改后的伤害数值（≥0）
     */
    float modify(LivingEntity target, DamageSource source, float amount);
}
