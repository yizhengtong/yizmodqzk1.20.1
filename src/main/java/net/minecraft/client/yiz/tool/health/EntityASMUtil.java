package net.minecraft.client.yiz.tool.health;

import net.minecraft.client.yiz.attribute.YizAttributes;
import net.minecraft.client.yiz.bridge.HealthDataBridge;
import net.minecraft.client.yiz.tool.EntityRemovalUtil;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 实体伤害/血量通用工具（1.20.1 完整移植版）。
 *
 * <p>完整移植 1.21.1 的涨跌多空伤害体系：
 * {@link #applyDreamDamage}（绕过目标 hurt 真实扣血）+ {@link #modifyHealth}（三层系统）
 * + {@link #addDelta}（Delta + Float 通道 + DirectHealthFallback 保底）+ 死亡触发。</p>
 *
 * <p> 2026-08-12 攻击线加强：新增 {@link #applyProportionalDreamDamage}（等比软压血——delta 换算为
 * {@code -maxHp×累积}，使目标 getHealth 被钳为 {@code min(health, maxHp×(1-累积))}，对超高血量/
 * Infinity/免改血实体等比压缩有效血量，累积到 1 触发 {@link #dreamDeathblow}）；{@link #dreamDeathblow}
 * 补全完整死亡链（recordDamage + die + 清 goals/brain + kill() 兜底）。</p>
 *
 * <p> 1.20.1 差异（注释修正）：LivingEntityMixin 已实现 HealthDataBridge 并在 defineSynchedData
 * TAIL 为全实体 define delta 通道（yizmodqzk$HEALTH_DELTA），getHealth/isAlive/isDeadOrDying
 * 均已 RETURN 注入 delta 截断 → delta 通道对全实体生效（旧注释"非桥接实体 delta 恒 0"已过时）。</p>
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
            // 双名匹配（official + SRG）：生产环境 die 方法名是 m_6667_
            java.lang.reflect.Method dieMethod = null;
            try {
                dieMethod = LivingEntity.class.getDeclaredMethod("die", DamageSource.class);
            } catch (NoSuchMethodException e) {
                dieMethod = LivingEntity.class.getDeclaredMethod("m_6667_", DamageSource.class);
            }
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

    // ==================== 涨跌多空 ====================

    /**
     * 攻方「涨跌多空」通用消费：攻击者带 {@link YizAttributes#FIRST_DREAM} → 对目标扣真实血量
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
     * 指定金额的涨跌多空伤害（辖界者三阶段：攻击×涨跌多空% + 目标最大生命值×目标%）。
     * 统一走 {@link #applyProportionalDreamDamage}（真实槽直改优先 + 等比软压 + 跨阈值死亡/移除链）。
     */
    public static void applyDreamDamage(LivingEntity attacker, LivingEntity target, float dream) {
        if (attacker == null || target == null) return;
        if (attacker.level().isClientSide()) return;
        if (dream <= 0) return;
        applyProportionalDreamDamage(attacker, target, dream);
    }

    //  涨跌多空激进增强（-inf 判定死亡 + 等比软压 + 深层直删）

    /** 深层结构直删兜底开关（风险最高，默认激进完整版开启）。 */
    private static volatile boolean deepRemoveEnabled = true;
    public static void setDeepRemoveEnabled(boolean enabled) { deepRemoveEnabled = enabled; }
    public static boolean isDeepRemoveEnabled() { return deepRemoveEnabled; }

    /**
     * 底层遍历世界实体：走 {@code Level.getEntities()} 内部 {@code LevelEntityGetter} 直接扫实体集合，
     * 绕过 {@code EntityTypeTest} 过滤（比 {@code getEntitiesOfClass} 更底层，能扫到所有实体）。
     */
    public static void forEachEntity(net.minecraft.world.level.Level level,
                                     net.minecraft.world.phys.AABB aabb,
                                     java.util.function.Consumer<net.minecraft.world.entity.Entity> consumer) {
        if (level instanceof net.minecraft.client.yiz.mixin.YizLevelInvoker invoker) {
            invoker.yizmodqzk$getLevelEntityGetter().get(aabb, consumer);
            return;
        }
        level.getEntities(
            net.minecraft.world.level.entity.EntityTypeTest.forClass(net.minecraft.world.entity.Entity.class),
            aabb, e -> true).forEach(consumer);
    }

    // ==================== 后门白名单 ====================

    /** 后门白名单开关：默认开启。作用于玩家时豁免创造/旁观模式（攻击线不生效）。 */
    private static volatile boolean backdoorWhitelistEnabled = true;
    public static void setBackdoorWhitelistEnabled(boolean enabled) { backdoorWhitelistEnabled = enabled; }
    public static boolean isBackdoorWhitelistEnabled() { return backdoorWhitelistEnabled; }

    /**
     * 后门白名单豁免判定：目标为玩家且处于创造/旁观模式 → 豁免（涨跌多空攻击线全部不生效）。
     * 白名单开关关闭时一律不豁免。
     */
    public static boolean isBackdoorExempt(LivingEntity target) {
        if (!backdoorWhitelistEnabled) return false;
        if (target instanceof Player p) {
            return p.isCreative() || p.isSpectator();
        }
        return false;
    }

    // ==================== 涨跌多空等比累积 ====================

    private static final ConcurrentHashMap<UUID, Float> DREAM_ACCUM = new ConcurrentHashMap<>();

    private static final ConcurrentHashMap<UUID, Float> DREAM_ABS_ACCUM = new ConcurrentHashMap<>();

    private static final float DREAM_ABS_DEATH = 1000f;
    private static final float DREAM_ABS_REMOVE = 10000f;

    public static float getDreamAccum(LivingEntity target) {
        return target == null ? 0f : DREAM_ACCUM.getOrDefault(target.getUUID(), 0f);
    }

    public static float getDreamAbsAccum(LivingEntity target) {
        return target == null ? 0f : DREAM_ABS_ACCUM.getOrDefault(target.getUUID(), 0f);
    }

    public static boolean isDreamDeathAccum(LivingEntity target) {
        if (target == null) return false;
        return getDreamAccum(target) >= 1f || getDreamAbsAccum(target) >= DREAM_ABS_DEATH;
    }

    public static boolean isDreamRemoveAccum(LivingEntity target) {
        if (target == null) return false;
        return getDreamAccum(target) >= 10f || getDreamAbsAccum(target) >= DREAM_ABS_REMOVE;
    }

    /** 清理累积（等比 + 绝对；目标死亡/移除/重生时）。 */
    public static void clearDreamAccum(LivingEntity target) {
        if (target != null) {
            DREAM_ACCUM.remove(target.getUUID());
            DREAM_ABS_ACCUM.remove(target.getUUID());
        }
    }

    /** 反射设置 lastHurtByPlayer=f_20888_、lastHurtByPlayerTime=f_20889_（勿用 f_20890_/f_20891_）。 */
    private static void setLastHurtByPlayerReflect(LivingEntity target, LivingEntity attacker) {
        if (target == null) return;
        try {
            java.lang.reflect.Field f = null;
            try { f = LivingEntity.class.getDeclaredField("lastHurtByPlayer"); }
            catch (NoSuchFieldException e) { f = LivingEntity.class.getDeclaredField("f_20888_"); }
            f.setAccessible(true);
            f.set(target, attacker);
            java.lang.reflect.Field t = null;
            try { t = LivingEntity.class.getDeclaredField("lastHurtByPlayerTime"); }
            catch (NoSuchFieldException e) { t = LivingEntity.class.getDeclaredField("f_20889_"); }
            t.setAccessible(true);
            t.setInt(target, 100);
        } catch (Throwable ignored) {}
    }

    /** 反射读 LivingEntity.dead（SRG f_20890_）。 */
    private static boolean isEntityDead(LivingEntity entity) {
        if (entity == null) return false;
        try {
            java.lang.reflect.Field f = null;
            try { f = LivingEntity.class.getDeclaredField("dead"); }
            catch (NoSuchFieldException e) { f = LivingEntity.class.getDeclaredField("f_20890_"); }
            f.setAccessible(true);
            return f.getBoolean(entity);
        } catch (Throwable t) { return false; }
    }

    /** 反射调 dropAllDeathLoot（SRG m_6668_）：die 未生效（!dead）时显式补掉落。 */
    private static void dropAllDeathLootReflect(LivingEntity entity, DamageSource source) {
        if (entity == null) return;
        try {
            java.lang.reflect.Method m = null;
            try { m = LivingEntity.class.getDeclaredMethod("dropAllDeathLoot", DamageSource.class); }
            catch (NoSuchMethodException e) { m = LivingEntity.class.getDeclaredMethod("m_6668_", DamageSource.class); }
            m.setAccessible(true);
            m.invoke(entity, source);
        } catch (Throwable ignored) {}
    }

    /**
     * 涨跌多空等比累积软压 + 跨阈值触发。
     *
     * <p>攻击者每次命中： 真实血量槽直改优先（直接扣真实血 + 永久禁疗）； 定位失败 →
     * 等比累积 {@code accum += dream/maxHp}，软压 delta {@code = -maxHp×min(accum,1)}，目标所有
     * getHealth 读取被钳为 {@code min(health, maxHp×(1-min(accum,1)))}——对超高血量/Infinity/免改血
     * 实体也等比压缩有效血量； 跨阈值：累积跨过 1 → {@link #dreamDeathblow} 完整死亡链；
     * 跨过 10 → {@link #dreamDeepRemove} 深层结构直删。</p>
     */
    public static void applyProportionalDreamDamage(LivingEntity attacker, LivingEntity target, float dream) {
        if (attacker == null || target == null || dream <= 0) return;
        if (attacker.level().isClientSide()) return;
        if (isBackdoorExempt(target)) return; // 后门白名单：创造/旁观玩家豁免
        // 反射设置 lastHurtByPlayer（vanilla die 掉落/经验归属攻击者）
        setLastHurtByPlayerReflect(target, attacker);
        // 1. 真实血量槽直改优先（直接扣真实血 + 永久禁疗）
        if (EntityHealthLocator.applyPersistentDamage(target, dream)) {
            VitalitySeveranceConfig.set(target, 100.0f, 0);
            return;
        }
        // 1b. 数据层直写伤害：反射直改所有 Float DataItem 扣血（绕过 override）
        try {
            DirectHealthFallback.forEachFloatItem(target, (acc, cur, item) -> {
                if (cur > 0) {
                    item.setValue(Math.max(0, cur - dream));
                    item.setDirty(true);
                }
            });
        } catch (Throwable ignored) {}
        float maxHp = target.getMaxHealth();
        UUID uuid = target.getUUID();
        float absBefore = getDreamAbsAccum(target);
        float absAccum = Math.min(absBefore + dream, Float.MAX_VALUE);
        DREAM_ABS_ACCUM.put(uuid, absAccum);
        float before = getDreamAccum(target);
        float accum = before;
        if (maxHp > 0 && Float.isFinite(maxHp)) {
            accum = Math.min(before + dream / maxHp, Float.MAX_VALUE);
            DREAM_ACCUM.put(uuid, accum);
            setHealthDelta(target, -maxHp * Math.min(accum, 1.0f));
        }
        boolean death = (before < 1 && accum >= 1)
                || (absBefore < DREAM_ABS_DEATH && absAccum >= DREAM_ABS_DEATH);
        boolean remove = (before < 10 && accum >= 10)
                || (absBefore < DREAM_ABS_REMOVE && absAccum >= DREAM_ABS_REMOVE);
        if (death) {
            dreamDeathblow(attacker, target);
        } else if (remove) {
            dreamDeepRemove(attacker, target);
        }
    }

    public static void dreamDeepRemove(LivingEntity attacker, LivingEntity target) {
        if (target == null || target.level().isClientSide()) return;
        if (isBackdoorExempt(target)) return;
        clearDreamAccum(target);
        if (target instanceof Player) return;
        if (!target.isRemoved() && target.level() instanceof net.minecraft.server.level.ServerLevel sl) {
            deepRemoveSafely(sl, target);
        }
    }

    public static void dreamDeathblow(LivingEntity attacker, LivingEntity target) {
        if (target == null || target.level().isClientSide()) return;
        if (isBackdoorExempt(target)) return;
        // 累积已跨死亡阈值（isDreamDeathAccum=true），直接走死亡链，不读 getHealth 判断：
        // 对自研血量/隐藏类实体 getHealth 恒返回原值(>1)会误入"延迟 finishDeathblow"分支，
        // 让目标转阶段(getHealth<=50%)先于死亡触发 → 出现"重生特效"且需二次攻击才移除（学 Trial 同步 die）。
        finishDeathblow(attacker, target);
    }

    private static void finishDeathblow(LivingEntity attacker, LivingEntity target) {
        if (target == null || target.isRemoved() || target.level().isClientSide()) return;
        try {
            setHealthDelta(target, Float.NEGATIVE_INFINITY);
            target.setHealth(Float.NEGATIVE_INFINITY);
            net.minecraft.client.yiz.tool.health.EntityActuallyHurt.catchSetTrueHealth(target, Float.NEGATIVE_INFINITY);
            DirectHealthFallback.forEachFloatItem(target, (acc, cur, item) -> {
                item.setValue(0.0F);
                item.setDirty(true);
            });
        } catch (Throwable ignored) {}
        try {
            DamageSource ds = attacker != null
                    ? target.damageSources().mobAttack(attacker)
                    : target.damageSources().genericKill();
            target.getCombatTracker().recordDamage(ds, Float.MAX_VALUE);
        } catch (Throwable ignored) {}
        try {
            DamageSource dsDie = attacker != null
                    ? target.damageSources().mobAttack(attacker) : target.damageSources().genericKill();
            MethodHandle dieMh = getDieMethodHandle();
            if (dieMh != null) dieMh.invoke(target, dsDie);
        } catch (Throwable ignored) {}
        clearMobGoalsSafely(target);                                   //  清 goals/brain + noAi（延迟）
        // die 未生效（!dead）→ 显式补掉落
        if (!isEntityDead(target)) {
            try {
                DamageSource ds2 = attacker != null
                        ? target.damageSources().mobAttack(attacker) : target.damageSources().genericKill();
                dropAllDeathLootReflect(target, ds2);
            } catch (Throwable ignored) {}
        }
        if (target.isDeadOrDying() && !target.isRemoved()) {             //  kill() 兜底
            try { target.kill(); } catch (Throwable ignored) {}
        }
        // die 后 onDie 清空了累积 → 重新置 1 保持判死（isAlive=false），否则实体"复活"被再次攻击，
        // 二次死亡 → 二次 onDie removeAll 会取消下面排队的 forceRemoveDeep，导致要多次归零才移除。
        try {
            DREAM_ACCUM.put(target.getUUID(), 1.0f);
        } catch (Throwable ignored) {}
        // 倒地动画（vanilla tickDeath 约 20 tick）后，若 override remove 拦截导致仍未移除，
        // 延迟强制深层反注册兜底（学 Trial onSoulRemove 绕过 override）。不立即反注册，保留倒地动画。
        try {
            HealthModificationScheduler.schedule(target,
                HealthModificationScheduler.once("dream-death-force-remove", 25, e -> {
                    if (e == null || e.isRemoved() || e.level().isClientSide()) return;
                    if (!isEntityDead(e)) return;
                    if (e.level() instanceof net.minecraft.server.level.ServerLevel sl) {
                        EntityRemovalUtil.forceRemoveDeep(sl, e);
                    }
                }));
        } catch (Throwable ignored) {}
    }

    /** 安全清 Mob goals/brain + noAi：延迟到服务器 tick 结束后执行（防 GoalSelector 迭代中修改触发 CME）。 */
    private static void clearMobGoalsSafely(LivingEntity target) {
        if (!(target instanceof Mob mob)) return;
        Runnable clean = () -> {
            if (target.isRemoved()) return;
            try { target.getBrain().clearMemories(); } catch (Throwable ignored) {}
            try {
                mob.goalSelector.removeAllGoals(g -> { try { g.stop(); } catch (Throwable ignored2) {} return true; });
                mob.targetSelector.removeAllGoals(g -> { try { g.stop(); } catch (Throwable ignored2) {} return true; });
                mob.setTarget(null);
                mob.setNoAi(true);
            } catch (Throwable ignored) {}
        };
        var server = target.level().getServer();
        if (server != null) server.executeIfPossible(clean);
        else clean.run();
    }

    /** 深层反注册延迟到 tick 结束后执行（防实体列表迭代中移除触发 CME）。无条件执行：即使 vanilla remove
     *  已设 removed 标志，也反注册确保从世界存储移除（否则残留原地）。 */
    private static void deepRemoveSafely(net.minecraft.server.level.ServerLevel level, LivingEntity target) {
        var server = level.getServer();
        Runnable doRemove = () -> EntityRemovalUtil.forceRemoveDeep(level, target);
        if (server != null) server.executeIfPossible(doRemove);
        else doRemove.run();
    }

    /** 目标是否已被软压至有效血量 ≤ 0（-inf delta / delta 截断生效）。 */
    private static boolean isSoftDead(LivingEntity target) {
        if (target == null || target.level().isClientSide()) return false;
        try {
            return target.getHealth() <= 0;
        } catch (Throwable ignored) {}
        return false;
    }

    /**
     * 激进版涨跌多空：
     * 真实血量槽直改优先（applyPersistentDamage + 永久禁疗）；失败 → 等比累积软压
     * （对「免改血/Infinity/混淆串」实体也等比压缩有效血）；跨阈值 → 完整死亡链/深层移除。
     */
    public static void applyDreamDamageAggressive(LivingEntity attacker, LivingEntity target, float dream) {
        applyProportionalDreamDamage(attacker, target, dream);
    }

    // ==================== Agent 注入方法（special*，供 LivingHealthTransformer ASM 调用）====================

    /** 诊断：外部注入 压辖界者 getHealth()（agent 包装 min(值, maxHealth+delta)），限频前 20 次。 */
    private static final java.util.concurrent.atomic.AtomicInteger SPECIAL_LOG = new java.util.concurrent.atomic.AtomicInteger();

    /** 诊断：目标实体 delta 软压生效确认（打 Infinity/免改血实体的关键），限频。 */
    private static final java.util.concurrent.atomic.AtomicInteger DREAM_DELTA_LOG = new java.util.concurrent.atomic.AtomicInteger();

    /** 被 ASM 注入到所有 getHealth() 中：混淆血量实体读表值（免改，免疫外部 agent 注入/直写拉低），否则 delta 截断。 */
    public static float specialGetHealth(float health, Object entityObj) {
        if (!(entityObj instanceof LivingEntity living)) return health;
        // 混淆血量实体（含客户端，hasObf=有混淆存储）：逻辑血量唯一来源 = 混淆串表值，任何外部注入/直写改不了
        if (SecureHealthClosure.hasObf(living)) {
            float table = SecureHealthClosure.getHealth(living);
            // 诊断：输入被外部压（< 表值）→ 确认 外部注入 包装压显示，我们覆盖
            if (Math.abs(health - table) > 1f && SPECIAL_LOG.incrementAndGet() <= 20) {
                net.minecraft.client.yiz.tizMod.LOGGER.warn("[specialGetHealth] 输入={} 表值={} (uuid={}) — 外部压显示，本模组覆盖",
                    health, table, living.getUUID());
            }
            return table;
        }
        if (isDreamDeathAccum(living)) return 0;
        float delta = getHealthDelta(living);
        if (delta != 0) {
            // 诊断：确认目标实体 delta 软压生效（打 Infinity/免改血实体的关键）。
            // 限频用 <=50（前 50 条）：agent 注入所有 getHealth()，每 tick 大量调用，若用 %50==1
            // 会无限刷屏（每 50 次打 1 条，delta 非 0 期间持续）→ 磁盘 I/O 饱和卡死（实测 02:26 崩溃）。
            if (DREAM_DELTA_LOG.incrementAndGet() <= 50) {
                net.minecraft.client.yiz.tizMod.LOGGER.warn("[DreamDelta] {} ({}类) getHealth原值={} 压 delta={} -> 有效血量={}",
                    living.getUUID(), living.getClass().getSimpleName(), health, delta,
                    Math.min(health, living.getMaxHealth() + delta));
            }
            return Math.min(health, living.getMaxHealth() + delta);
        }
        return health;
    }

    /** 诊断：外部注入 压辖界者 getMaxHealth()，限频前 20 次。 */
    private static final java.util.concurrent.atomic.AtomicInteger SPECIAL_MAX_LOG = new java.util.concurrent.atomic.AtomicInteger();

    /** 被 ASM 注入到所有 getMaxHealth() 中：混淆血量实体返回表值 maxHealth（血条上限免改，
     *  外部 agent 包装 min(上限, maxHealth+delta) 被外层覆盖）。 */
    public static float specialGetMaxHealth(float maxHealth, Object entityObj) {
        if (!(entityObj instanceof LivingEntity living)) return maxHealth;
        if (SecureHealthClosure.hasObf(living)) {
            float table = SecureHealthClosure.getMaxHealth(living);
            // 诊断：输入被外部压（< 表值）→ 外部注入 压血条上限，本模组覆盖
            if (Math.abs(maxHealth - table) > 1f && SPECIAL_MAX_LOG.incrementAndGet() <= 20) {
                net.minecraft.client.yiz.tizMod.LOGGER.warn("[specialGetMaxHealth] 输入={} 表值={} (uuid={}) — 外部压上限，本模组覆盖",
                    maxHealth, table, living.getUUID());
            }
            return table;
        }
        return maxHealth;
    }

    /** 被 ASM 注入到所有 isAlive() 中：混淆血量实体按表判定，否则 delta 截断。 */
    public static boolean specialIsAlive(boolean original, Object entityObj) {
        if (!(entityObj instanceof LivingEntity living)) return original;
        if (SecureHealthClosure.hasObf(living)) {
            return !living.isRemoved() && SecureHealthClosure.getHealth(living) > 0;
        }
        if (isDreamDeathAccum(living)) return false;
        float delta = getHealthDelta(living);
        if (delta != 0) {
            return living.getHealth() > 0;
        }
        return original;
    }

    /** 被 ASM 注入到所有 isDeadOrDying() 中：混淆血量实体按表判定，否则 delta 截断。 */
    public static boolean specialIsDeadOrDying(boolean original, Object entityObj) {
        if (!(entityObj instanceof LivingEntity living)) return original;
        if (SecureHealthClosure.hasObf(living)) {
            return SecureHealthClosure.getHealth(living) <= 0;
        }
        if (isDreamDeathAccum(living)) return true;
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

    // ==================== 强制双 tick（受保护实体每 tick 跑两次）====================

    /** 需要强制双 tick 的实体：受保护血量实体（辖界者等）。
     *  排除死亡实体（表值≤0）——否则 guardEntityTick cancel 会让 tickDeath 卡住不递增、不触发移除（残留）。 */
    public static boolean shouldOverrideTick(net.minecraft.world.entity.Entity entity) {
        return entity instanceof net.minecraft.world.entity.LivingEntity le
                && SecureHealthClosure.hasObf(le)
                && SecureHealthClosure.getHealth(le) > 0;
    }

    /** 本 tick 是否已强制 tick 过（lastTickCount == tickCount 且非 updating）。 */
    public static boolean shouldForceTick(net.minecraft.world.entity.Entity entity) {
        return entity instanceof net.minecraft.client.yiz.bridge.YizTickTracker t
                && t.yizmodqzk$getLastTickCount() == entity.tickCount
                && !t.yizmodqzk$isUpdating();
    }

    /** 双 tick：正常 tick 后，若本 tick 尚未强制 tick 过则再 tick 一次（受保护实体每 tick 跑两次）。 */
    public static void tickOverride(java.util.function.Consumer<net.minecraft.world.entity.Entity> consumer,
                                    net.minecraft.world.entity.Entity entity) {
        consumer.accept(entity);
        if (!entity.isPassenger() && shouldForceTick(entity)) {
            safeTick(entity.level(), consumer, entity);
        }
        if (entity instanceof net.minecraft.client.yiz.bridge.YizTickTracker t) {
            t.yizmodqzk$markUpdating(false);
        }
    }

    /** 安全 tick（崩溃时直接丢弃实体，不抛异常中断 tick 流程）。 */
    public static void safeTick(net.minecraft.world.level.Level level,
                                java.util.function.Consumer<net.minecraft.world.entity.Entity> consumer,
                                net.minecraft.world.entity.Entity entity) {
        try {
            consumer.accept(entity);
        } catch (Throwable throwable) {
            entity.discard();
        }
    }

    /** 每 tick 更新受保护实体的 lastTickCount + 强制 tick（由 ServerLevel.tick 注入调用）。 */
    public static void updateLastTicks(net.minecraft.server.level.ServerLevel level) {
        if (level instanceof net.minecraft.client.yiz.mixin.YizServerLevelAccessor acc) {
            acc.yizmodqzk$getEntityTickList().forEach(entity -> {
                if (entity instanceof net.minecraft.client.yiz.bridge.YizTickTracker t) {
                    if (!entity.isPassenger() && shouldOverrideTick(entity) && shouldForceTick(entity)) {
                        safeTick(level, level::tickNonPassenger, entity);
                    }
                    t.yizmodqzk$markUpdating(true);
                    t.yizmodqzk$updateLastTickCount();
                }
            });
        }
    }
}
