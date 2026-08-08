package net.minecraft.client.yiz.api;

import net.minecraftforge.registries.RegistryObject;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 状态效果属性注册表。
 *
 * <p>将自定义属性绑定到状态效果类型。攻方属性由攻击者携带，攻击时对目标生效；
 * 防方属性由受击者携带，受击时对攻击者生效。</p>
 *
 * <h3>效果类型</h3>
 * <ul>
 *   <li>{@link StatusEffectType#STUN} — 眩晕</li>
 *   <li>{@link StatusEffectType#SLOW} — 减速</li>
 *   <li>{@link StatusEffectType#FREEZE} — 冰冻</li>
 *   <li>{@link StatusEffectType#SHOCK} — 感电</li>
 *   <li>{@link StatusEffectType#KNOCKBACK} — 击飞</li>
 * </ul>
 */
public final class StatusEffectAttributeRegistry {

    public enum StatusEffectType {
        STUN,
        SLOW,
        FREEZE,
        SHOCK,
        KNOCKBACK
    }

    /** 攻方属性条目：Holder → 效果类型 */
    private static final Map<RegistryObject<Attribute>, StatusEffectType> ATTACK_ENTRIES = new ConcurrentHashMap<>();

    /** 防方属性条目：Holder → 效果类型 */
    private static final Map<RegistryObject<Attribute>, StatusEffectType> DEFENSE_ENTRIES = new ConcurrentHashMap<>();

    private StatusEffectAttributeRegistry() {}

    /**
     * 注册攻方属性：攻击者携带此属性，攻击时对目标施加效果。
     */
    public static void registerAttack(RegistryObject<Attribute> holder, StatusEffectType type) {
        ATTACK_ENTRIES.put(holder, type);
    }

    /**
     * 注册防方属性：受击者携带此属性，被攻击时对攻击者施加效果。
     */
    public static void registerDefense(RegistryObject<Attribute> holder, StatusEffectType type) {
        DEFENSE_ENTRIES.put(holder, type);
    }

    /**
     * 获取攻击者身上所有已注册攻方属性的效果总值（按类型汇总）。
     */
    public static Map<StatusEffectType, Float> getAttackEffects(LivingEntity attacker) {
        Map<StatusEffectType, Float> result = new EnumMap<>(StatusEffectType.class);
        for (var entry : ATTACK_ENTRIES.entrySet()) {
            var inst = attacker.getAttribute(entry.getKey().get());
            if (inst == null) continue; // 实体未注册该属性（如怪物无 defense 系属性）→ 跳过，避免 getAttributeValue 抛 IllegalArgumentException
            double value = inst.getValue();
            if (value > 0) {
                result.merge(entry.getValue(), (float) value, Float::sum);
            }
        }
        return result;
    }

    /**
     * 获取受击者身上所有已注册防方属性的效果总值（按类型汇总）。
     */
    public static Map<StatusEffectType, Float> getDefenseEffects(LivingEntity defender) {
        Map<StatusEffectType, Float> result = new EnumMap<>(StatusEffectType.class);
        for (var entry : DEFENSE_ENTRIES.entrySet()) {
            var inst = defender.getAttribute(entry.getKey().get());
            if (inst == null) continue; // 实体未注册该属性 → 跳过（见 getAttackEffects 注释）
            double value = inst.getValue();
            if (value > 0) {
                result.merge(entry.getValue(), (float) value, Float::sum);
            }
        }
        return result;
    }
}
