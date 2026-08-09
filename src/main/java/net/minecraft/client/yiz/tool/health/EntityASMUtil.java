package net.minecraft.client.yiz.tool.health;

import net.minecraft.client.yiz.attribute.YizAttributes;
import net.minecraft.client.yiz.bridge.HealthDataBridge;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;

/**
 * 实体伤害/血量通用工具（1.20.1 完整移植版）。
 *
 * <p>完整移植 1.21.1 的最初梦幻伤害体系：
 * {@link #applyDreamDamage}（绕过目标 hurt 真实扣血）+ {@link #modifyHealth}（三层系统）
 * + {@link #addDelta}（Delta + Float 通道 + DirectHealthFallback 保底）+ 死亡触发。</p>
 *
 * <p>⚠️ 1.20.1 差异：HealthDataBridge（delta 通道）由实体自身实现，无 LivingEntityMixin 注入
 * → 非桥接实体 delta 通道不可用（恒 0），但 EntityHealthLocator 字段直改与 Float 通道扫描仍生效。</p>
 */
public final class EntityASMUtil {

    private EntityASMUtil() {}

    private static volatile boolean deathTriggerEnabled = true;
    public static boolean isDeathTriggerEnabled() { return deathTriggerEnabled; }
    public static void setDeathTriggerEnabled(boolean enabled) { deathTriggerEnabled = enabled; }

    private static volatile boolean damageEffectsEnabled = false;
    public static boolean isDamageEffectsEnabled() { return damageEffectsEnabled; }
    public static void setDamageEffectsEnabled(boolean enabled) { damageEffectsEnabled = enabled; }

    // ==================== Delta 管理 ====================

    /** 获取实体健康值偏移量（delta）。有效血量上限 = getMaxHealth() + delta。 */
    public static float getHealthDelta(LivingEntity entity) {
        if (entity instanceof HealthDataBridge bridge) {
            return bridge.yizmodqzk$getHealthDelta();
        }
        return 0F;
    }

    /** 设置健康值偏移量（仅 ≤0，仅服务端）。 */
    public static void setHealthDelta(LivingEntity entity, float value) {
        if (value > 0) return;
        if (entity.level().isClientSide()) return;
        if (entity instanceof HealthDataBridge bridge) {
            bridge.yizmodqzk$setHealthDelta(value);
        }
    }

    /**
     * 累加健康值偏移量（负值降上限，正值恢复）。自动裁剪：delta 不允许为正。
     * 同时对目标所有 Float DataParameter 施加等量伤害（覆盖其他模组自定义血量）。
     */
    public static void addDelta(LivingEntity entity, float amount) {
        if (entity.level().isClientSide()) return;

        if (amount < 0 && damageEffectsEnabled) {
            entity.hurtTime = 10;
            entity.hurtDuration = 10;
            entity.level().broadcastEntityEvent(entity, (byte) 2);
            entity.playSound(SoundEvents.GENERIC_HURT, 1.0f, 1.0f);
        }

        // 1. delta 偏移（对实现了 HealthDataBridge 的实体生效；1.20.1 无 mixin 注入 → 普通实体跳过）
        float current = getHealthDelta(entity);
        float newDelta = current + amount;
        if (newDelta > 0) {
            newDelta = 0;
        }
        if (entity instanceof HealthDataBridge bridge) {
            bridge.yizmodqzk$setHealthDelta(newDelta);
        }

        // 2. 通用打击：直接修改该实体上所有 Float DataParameter 通道
        try {
            for (EntityDataAccessor<Float> channel : HealthChannelScanner.getFloatChannels(entity)) {
                if (channel.getId() == DirectHealthFallback.DELTA_ACCESSOR_ID) continue;
                float value = entity.getEntityData().get(channel);
                float newValue = Math.max(0, value + amount);
                entity.getEntityData().set(channel, newValue);
            }
        } catch (Throwable ignored) {}

        // 3. 原版血量通道直改：绕过 SynchedEntityData.set()（自定义实体可能覆写 set() 限伤，
        //    如按 DataParameter 存血量的实体）。对「血量存 vanilla 通道」的自研实体是主扣血路径。
        try {
            DirectHealthFallback.damageVanillaHealth(entity, amount);
        } catch (Throwable ignored) {}

        // 4. 最终保底：反射直接修改 DataItem 内部值
        DirectHealthFallback.damageAll(entity, amount);

        try { VitalitySeveranceHandler.updateBaseline(entity); } catch (Throwable ignored) {}

        // 5. 死亡触发
        triggerDeathIfDead(entity);
    }

    /**
     * 正向或负向修改健康值。
     * delta<0 走完整三层系统（addDelta）；delta>0 直接改所有 Float 通道（治疗）。
     */
    public static void modifyHealth(LivingEntity entity, float delta) {
        if (entity.level().isClientSide()) return;

        if (delta < 0) {
            addDelta(entity, delta);
        } else if (delta > 0) {
            var ban = VitalitySeveranceConfig.get(entity);
            if (ban != null) {
                delta = ban.apply(delta);
                if (delta <= 0) return;
            }
            for (EntityDataAccessor<Float> channel : HealthChannelScanner.getFloatChannels(entity)) {
                float value = entity.getEntityData().get(channel);
                entity.getEntityData().set(channel, value + delta);
            }
            DirectHealthFallback.healAll(entity, delta);
            VitalitySeveranceHandler.updateBaseline(entity);
        }
    }

    // ==================== 死亡触发 ====================

    private static volatile MethodHandle dieMethodHandle;
    private static volatile boolean dieMethodLookupFailed;

    private static MethodHandle getDieMethodHandle() {
        if (dieMethodHandle != null || dieMethodLookupFailed) return dieMethodHandle;
        try {
            java.lang.reflect.Method dieMethod = LivingEntity.class.getDeclaredMethod("die", DamageSource.class);
            dieMethod.setAccessible(true);
            dieMethodHandle = MethodHandles.lookup().unreflect(dieMethod);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            dieMethodLookupFailed = true;
        }
        return dieMethodHandle;
    }

    /** 当 DELTA 伤害导致实体有效血量 ≤ 0 时，通过反射调用 die()。 */
    private static void triggerDeathIfDead(LivingEntity entity) {
        if (!deathTriggerEnabled) return;
        float delta = getHealthDelta(entity);
        if (delta >= 0) return;
        if (entity.getMaxHealth() + delta > 0.0F) return;

        MethodHandle mh = getDieMethodHandle();
        if (mh == null) return;
        try {
            mh.invoke(entity, entity.damageSources().generic());
        } catch (Throwable ignored) {
        }
    }

    // ==================== 最初梦幻 ====================

    /**
     * 攻方「最初梦幻」通用消费：攻击者带 {@link YizAttributes#FIRST_DREAM} → 对目标扣真实血量
     * （绕过目标 hurt 免疫）。
     *
     * <p><b>不依赖目标 hurt</b>：自研血量实体在无敌/免疫期间 hurt 返回 false 也能命中。</p>
     * <ul>
     *   <li>自研血量实体（EntityHealthLocator 定位到真实血量字段）→ 直接改字段 + 永久禁疗</li>
     *   <li>原版/未定位 → modifyHealth（Float 通道 + DirectHealthFallback）</li>
     * </ul>
     */
    public static void applyDreamDamage(LivingEntity attacker, LivingEntity target) {
        if (attacker == null || target == null) return;
        var inst = attacker.getAttribute(YizAttributes.FIRST_DREAM.get());
        if (inst == null || inst.getValue() <= 0) return;
        applyDreamDamage(attacker, target, (float) inst.getValue());
    }

    /**
     * 指定金额的最初梦幻伤害（辖界者三阶段：攻击×梦幻% + 目标最大生命值×目标%）。
     */
    public static void applyDreamDamage(LivingEntity attacker, LivingEntity target, float dream) {
        if (attacker == null || target == null) return;
        if (attacker.level().isClientSide()) return;
        if (dream <= 0) return;
        if (EntityHealthLocator.applyPersistentDamage(target, dream)) {
            VitalitySeveranceConfig.set(target, 100.0f, 0); // 永久禁疗，堵死目标回血
        } else {
            modifyHealth(target, -dream); // 原版/未定位：Float 通道 + 保底（持久）
        }
    }

    // ==================== Agent 注入方法（special*，供 LivingHealthTransformer ASM 调用）====================

    /** 被 ASM 注入到所有 getHealth() 中：delta 截断（有效血量 ≤ maxHealth + delta）。 */
    public static float specialGetHealth(float health, Object entityObj) {
        if (!(entityObj instanceof LivingEntity living)) return health;
        float delta = getHealthDelta(living);
        if (delta != 0) {
            return Math.min(health, living.getMaxHealth() + delta);
        }
        return health;
    }

    /** 被 ASM 注入到所有 isAlive() 中。 */
    public static boolean specialIsAlive(boolean original, Object entityObj) {
        if (!(entityObj instanceof LivingEntity living)) return original;
        float delta = getHealthDelta(living);
        if (delta != 0) {
            return living.getHealth() > 0;
        }
        return original;
    }

    /** 被 ASM 注入到所有 isDeadOrDying() 中。 */
    public static boolean specialIsDeadOrDying(boolean original, Object entityObj) {
        if (!(entityObj instanceof LivingEntity living)) return original;
        float delta = getHealthDelta(living);
        if (delta != 0) {
            return living.getHealth() <= 0;
        }
        return original;
    }

    /** CoreMod 级 getHealth 修正：delta 截断 + 禁疗时封顶 maxHealth。 */
    public static float specialCoreModGetHealth(float originalHealth, LivingEntity entity) {
        float delta = getHealthDelta(entity);
        float afterDelta = (delta != 0)
            ? Math.min(originalHealth, entity.getMaxHealth() + delta)
            : originalHealth;
        if (VitalitySeveranceConfig.get(entity) != null) {
            return Math.min(afterDelta, entity.getMaxHealth());
        }
        return afterDelta;
    }

    // ==================== ASM 级禁疗注入标记 ====================

    private static final ThreadLocal<Boolean> HEAL_BAN_APPLIED_BY_ASM = ThreadLocal.withInitial(() -> false);

    /** ASM 注入 setHealth 时调用：标记本次已由 ASM 处理禁疗。 */
    @SuppressWarnings("unused")
    public static void markVitalitySeveranceApplied() {
        HEAL_BAN_APPLIED_BY_ASM.set(true);
    }

    /** 消费禁疗注入标记（Mixin setHealth 检查后清除）。 */
    public static boolean consumeVitalitySeveranceFlag() {
        boolean v = HEAL_BAN_APPLIED_BY_ASM.get();
        HEAL_BAN_APPLIED_BY_ASM.set(false);
        return v;
    }

    // ==================== 护甲穿透 / 保护态 ====================

    private static final ThreadLocal<Boolean> BYPASS_PROTECTION = ThreadLocal.withInitial(() -> false);

    private static final ThreadLocal<Float> ARMOR_PEN_PCT = ThreadLocal.withInitial(() -> 0f);
    private static final ThreadLocal<Float> ARMOR_PEN_FLAT = ThreadLocal.withInitial(() -> 0f);

    public static void setArmorPenetration(float pct, float flat) {
        ARMOR_PEN_PCT.set(pct);
        ARMOR_PEN_FLAT.set(flat);
    }
    public static float peekArmorPenPct() { return ARMOR_PEN_PCT.get(); }
    public static float peekArmorPenFlat() { return ARMOR_PEN_FLAT.get(); }
    public static void clearArmorPenetration() {
        ARMOR_PEN_PCT.set(0f);
        ARMOR_PEN_FLAT.set(0f);
    }

    public static void beginBypassProtection() { BYPASS_PROTECTION.set(true); }
    public static void endBypassProtection() { BYPASS_PROTECTION.remove(); }

    /** 确保生命值 never NaN，通常 never <1（保护态），beginBypassProtection 可临时放行。 */
    @SuppressWarnings("unused")
    public static float clampProtectedHealth(float health) {
        if (Float.isNaN(health)) return 1.0F;
        if (BYPASS_PROTECTION.get()) return health;
        if (health < 1.0F) return 1.0F;
        return health;
    }
}
