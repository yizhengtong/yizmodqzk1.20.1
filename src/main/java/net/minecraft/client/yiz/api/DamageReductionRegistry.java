package net.minecraft.client.yiz.api;

import net.minecraft.world.entity.LivingEntity;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 伤害减免注册表。
 * <p>由 {@code LivingEntityMixin.modifyHealthForVitalitySeverance}（setHealth 拦截层）调用。
 * 下游通过 {@link #register(HealthModifier)} 注册减免逻辑（如饰品装备减伤）。</p>
 */
public final class DamageReductionRegistry {

    private static final List<HealthModifier> MODIFIERS = new CopyOnWriteArrayList<>();

    private static volatile boolean ABOLISHED = false;

    public static void setAbolished(boolean abolished) {
        ABOLISHED = abolished;
    }

    public static boolean isAbolished() {
        return ABOLISHED;
    }

    private DamageReductionRegistry() {}

    @FunctionalInterface
    public interface HealthModifier {
        float modify(LivingEntity entity, float oldHealth, float newHealth);
    }

    public static void register(HealthModifier modifier) {
        MODIFIERS.add(modifier);
    }

    /** 由 setHealth 拦截层（Mixin）调用。仅扣血方向生效。末尾 clamp 到 ≥0。 */
    public static float applyBeforeSetHealth(LivingEntity entity, float newHealth) {
        if (ABOLISHED) return newHealth;
        if (MODIFIERS.isEmpty()) return newHealth;

        float oldHealth = entity.getHealth();
        if (newHealth >= oldHealth) return newHealth;

        float result = newHealth;
        for (HealthModifier modifier : MODIFIERS) {
            result = modifier.modify(entity, oldHealth, result);
        }
        return Math.max(0, result);
    }
}
