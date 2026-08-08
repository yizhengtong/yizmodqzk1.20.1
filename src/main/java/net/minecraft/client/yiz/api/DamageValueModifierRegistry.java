package net.minecraft.client.yiz.api;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 伤害数值修改注册表
 * <p>
 * 在 {@code LivingEntity.hurt()} 入口处（护甲/附魔计算之前）拦截伤害数值，
 * 允许下游模组根据伤害来源和数值进行修改。
 * </p>
 *
 * <p>
 * 调用位置：{@code LivingEntityMixin.yizmodqzk$modifyHurtAmount} 开头，
 * 在所有内置伤害修正（物品%增幅/减免）之前执行。
 * </p>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 远程伤害增幅：每层星辰 8%
 * DamageValueModifierRegistry.register((target, source, amount) -> {
 *     if (DamageSourceAPI.isPlayerAttacker(source)
 *             && DamageSourceAPI.isIndirectAttack(source)) {
 *         return amount * 1.5f;
 *     }
 *     return amount;
 * });
 * }</pre>
 */
public final class DamageValueModifierRegistry {

    private static final List<DamageValueModifier> MODIFIERS = new CopyOnWriteArrayList<>();

    private DamageValueModifierRegistry() {}

    /**
     * 注册一个伤害数值修改器。
     * 修改器按注册顺序执行，前一个的输出作为后一个的输入。
     */
    public static void register(DamageValueModifier modifier) {
        MODIFIERS.add(modifier);
    }

    /**
     * 在 hurt() 入口处调用，依次应用所有已注册的修改器。
     * 由 {@code LivingEntityMixin.yizmodqzk$modifyHurtAmount} 调用。
     *
     * @param target 承受伤害的实体
     * @param source 伤害来源
     * @param amount 当前伤害数值
     * @return 应用所有修改器后的最终数值（≥0）
     */
    public static float apply(LivingEntity target, DamageSource source, float amount) {
        if (MODIFIERS.isEmpty() || amount <= 0) return amount;
        for (DamageValueModifier modifier : MODIFIERS) {
            amount = modifier.modify(target, source, amount);
            if (amount <= 0) return 0;
        }
        return amount;
    }
}
