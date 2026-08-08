package net.minecraft.client.yiz.api;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraftforge.registries.RegistryObject;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * 禁疗属性注册表（1.20.1 移植版）。
 * <p>注册的属性自动作为禁疗源：攻击者每近战攻击时，自动计算所有已注册属性总和为目标施加禁疗配置。</p>
 */
public final class VitalitySeveranceAttributeRegistry {

    private static final Map<RegistryObject<Attribute>, Float> PERCENT_ENTRIES = new ConcurrentHashMap<>();
    private static final Map<RegistryObject<Attribute>, Float> FIXED_ENTRIES = new ConcurrentHashMap<>();

    private VitalitySeveranceAttributeRegistry() {}

    /** 注册百分比禁疗属性：攻击者每 1 点属性，目标受 scale% 治疗削减。 */
    public static void registerPercent(RegistryObject<Attribute> holder, float scale) {
        PERCENT_ENTRIES.put(holder, scale);
    }

    /** 注册固定值禁疗属性：攻击者每 1 点属性，目标每次治疗削减 scale 点。 */
    public static void registerFixed(RegistryObject<Attribute> holder, float scale) {
        FIXED_ENTRIES.put(holder, scale);
    }

    /** 获取攻击者所有已注册百分比禁疗属性总值（已乘缩放系数）。 */
    public static float getPercentTotal(LivingEntity attacker) {
        double total = 0;
        for (var entry : PERCENT_ENTRIES.entrySet()) {
            total += attacker.getAttributeValue(entry.getKey().get()) * entry.getValue();
        }
        return (float) total;
    }

    /** 获取攻击者所有已注册固定值禁疗属性总值（已乘缩放系数）。 */
    public static float getFixedTotal(LivingEntity attacker) {
        double total = 0;
        for (var entry : FIXED_ENTRIES.entrySet()) {
            total += attacker.getAttributeValue(entry.getKey().get()) * entry.getValue();
        }
        return (float) total;
    }

    public static Map<RegistryObject<Attribute>, Float> getRegisteredPercent() {
        return Map.copyOf(PERCENT_ENTRIES);
    }

    public static Map<RegistryObject<Attribute>, Float> getRegisteredFixed() {
        return Map.copyOf(FIXED_ENTRIES);
    }
}
