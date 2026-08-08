package net.minecraft.client.yiz.core;

import net.minecraft.client.yiz.api.StatusEffectAttributeRegistry.StatusEffectType;
import net.minecraft.world.entity.LivingEntity;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 状态效果调度器（1.20.1 精简版）。
 *
 * <p>完整版在 1.21.1 与攻击链路/感电粒子深度耦合；本次只移植辖界者所需的
 * {@link #hasHardControl}（硬控判定）。控制计时写入入口 {@link #applyControlTick} 预留，
 * 供后续特效系统接入。</p>
 */
public final class StatusEffectDispatcher {

    /** 状态伤害派发标记：状态伤害造成的 hurt 不再次派发（防递归）。 */
    public static final ThreadLocal<Boolean> DISPATCHING = ThreadLocal.withInitial(() -> false);

    private StatusEffectDispatcher() {}

    /** UUID → 类型 → 剩余 tick。 */
    private static final Map<UUID, EnumMap<StatusEffectType, Integer>> CONTROL_TIMERS = new ConcurrentHashMap<>();

    /**
     * 攻方效果派发：对目标施加状态效果（按概率掷骰）。状态伤害造成的 hurt 不再次派发（防递归）。
     * 1.20.1 简化：掷骰命中后调用 applyControlTick 记录硬控计时（STUN/FREEZE 影响移动），
     * 完整效果（伤害/粒子）后续按需补。
     */
    public static void dispatchToTarget(LivingEntity target, Map<StatusEffectType, Float> effects,
                                        LivingEntity source) {
        if (DISPATCHING.get()) return;
        for (var entry : effects.entrySet()) {
            float chance = entry.getValue();
            if (chance <= 0 || Math.random() * 100.0 >= chance) continue;
            applyControlTick(target, entry.getKey(), 40); // 2 秒硬控（简化）
        }
    }

    /** 防方效果派发：受击时对攻击者施加效果（按概率掷骰）。 */
    public static void dispatchToAttacker(LivingEntity attacker, Map<StatusEffectType, Float> effects,
                                          LivingEntity defender) {
        if (DISPATCHING.get()) return;
        for (var entry : effects.entrySet()) {
            float chance = entry.getValue();
            if (chance <= 0 || Math.random() * 100.0 >= chance) continue;
            applyControlTick(attacker, entry.getKey(), 40);
        }
    }

    /** 是否有硬控（眩晕/冰冻）——需要完全冻结物理移动。 */
    public static boolean hasHardControl(LivingEntity entity) {
        var timers = CONTROL_TIMERS.get(entity.getUUID());
        if (timers == null || timers.isEmpty()) return false;
        return timers.containsKey(StatusEffectType.STUN)
            || timers.containsKey(StatusEffectType.FREEZE);
    }

    /** 设置某类型控制剩余 tick（>0 则记录，≤0 移除）。 */
    public static void applyControlTick(LivingEntity entity, StatusEffectType type, int ticks) {
        if (entity == null || entity.level().isClientSide()) return;
        var timers = CONTROL_TIMERS.computeIfAbsent(entity.getUUID(), k -> new EnumMap<>(StatusEffectType.class));
        if (ticks <= 0) {
            timers.remove(type);
        } else {
            timers.put(type, ticks);
        }
    }

    /** 每 tick：所有控制计时递减，归零移除。由实体 tick 或外部事件调用。 */
    public static void tickControlTimers(LivingEntity entity) {
        var timers = CONTROL_TIMERS.get(entity.getUUID());
        if (timers == null || timers.isEmpty()) return;
        timers.entrySet().removeIf(e -> {
            int next = e.getValue() - 1;
            if (next <= 0) return true;
            e.setValue(next);
            return false;
        });
    }

    /** 实体下线/移除时清理。 */
    public static void clear(LivingEntity entity) {
        CONTROL_TIMERS.remove(entity.getUUID());
    }
}
