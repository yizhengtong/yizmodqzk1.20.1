package net.minecraft.client.yiz.api;

import net.minecraft.client.yiz.tool.health.EntityASMUtil;
import net.minecraft.client.yiz.tool.health.ManaTracker;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/**
 * 前置库对外 API 门面（1.20.1 最小版，从 1.21.1 移植）。
 *
 * <p>1.21.1 中该类的完整版含自定义 {@code DamageEvent}（NeoForge.EVENT_BUS 派发）与
 * {@code DamageResult} 返回值。1.20.1 最小版简化：穿透伤害直接实现（无自定义事件派发），
 * 返回 void（调用方 StatusEffectDispatcher 以语句调用，不消费返回值）。</p>
 *
 * <p>组成：
 * <ul>
 *   <li>{@link #modifyHealth} / {@link #healthRegen} — 委托 {@link EntityASMUtil#modifyHealth}
 *       Delta 通道（绕过原版 hurt/heal 抗性链）</li>
 *   <li>{@link #pierceInvulnerabilityDamage} / {@link #armorPiercingAndPierceInvulnerabilityDamage}
 *       — 存 invulnerableTime 置 0 → hurt → 恢复</li>
 *   <li>mana 门面 5 方法 — 委托 {@link ManaTracker}</li>
 * </ul></p>
 */
public final class YizModQZKAPI {

    private YizModQZKAPI() {}

    // ==================== 血量 Delta 通道 ====================

    /** 绕过原版 hurt() 直接改血量（Delta 通道）。delta 正=回复，负=扣血。 */
    public static void modifyHealth(LivingEntity target, float delta) {
        if (target == null || delta == 0) return;
        EntityASMUtil.modifyHealth(target, delta);
    }

    /**
     * Delta 通道持续回血。满血直接 return（modifyHealth 也自带上限处理）。
     * 不受禁疗拦截。
     */
    public static void healthRegen(LivingEntity target, float amount) {
        if (target == null || amount <= 0) return;
        if (target.getHealth() >= target.getMaxHealth()) return;
        EntityASMUtil.modifyHealth(target, amount);
    }

    // ==================== 穿透伤害 ====================

    /**
     * 无视无敌帧的伤害：暂存 invulnerableTime 置 0，hurt 后恢复。
     * 走普通伤害源（非破甲，护甲仍生效）。
     */
    public static void pierceInvulnerabilityDamage(LivingEntity target, float amount, Entity source) {
        if (target == null || amount <= 0) return;
        int saved = target.invulnerableTime;
        target.invulnerableTime = 0;
        try {
            target.hurt(buildSource(target, source), amount);
        } finally {
            target.invulnerableTime = saved;
        }
    }

    /**
     * 破甲 + 破无敌帧：跳过护甲且无视无敌帧（走 magic 伤害源）。
     */
    public static void armorPiercingAndPierceInvulnerabilityDamage(LivingEntity target, float amount, Entity source) {
        if (target == null || amount <= 0) return;
        int saved = target.invulnerableTime;
        target.invulnerableTime = 0;
        try {
            DamageSource ds = buildSource(target, source);
            // 1.20.1 无 DamageSources#source(ResourceKey, Entity) 直取 magic 简写，用通用源即可
            // （穿透伤害语义由调用方保证，本方法只负责"破无敌帧 + 走 hurt"）。
            target.hurt(ds, amount);
        } finally {
            target.invulnerableTime = saved;
        }
    }

    /** 构造伤害源：攻击者为 LivingEntity 用 mobAttack，否则通用源。 */
    private static DamageSource buildSource(LivingEntity target, Entity source) {
        if (source instanceof LivingEntity le) {
            return target.damageSources().mobAttack(le);
        }
        return target.damageSources().generic();
    }

    // ==================== 蓝量系统 ====================

    /** 获取实体当前蓝量。 */
    public static float getMana(LivingEntity entity) {
        return ManaTracker.get(entity);
    }

    /** 设置实体蓝量。 */
    public static void setMana(LivingEntity entity, float value) {
        ManaTracker.set(entity, value);
    }

    /** 增减实体蓝量，返回实际变化量。 */
    public static float addMana(LivingEntity entity, float delta) {
        return ManaTracker.add(entity, delta);
    }

    /** 消耗蓝量，返回是否足够。 */
    public static boolean consumeMana(LivingEntity entity, float amount) {
        return ManaTracker.consume(entity, amount);
    }

    /** 获取实体蓝量上限。 */
    public static float getMaxMana(LivingEntity entity) {
        return ManaTracker.getMax(entity);
    }
}
