package net.minecraft.client.yiz.tool.health;

import net.minecraft.client.yiz.attribute.YizAttributes;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 传导限伤引擎 — 统一限伤核心（2026-08-07 重构终态，参考外部哈希表血量方案）。
 *
 * <p>**所有血量修改路径一律丢弃模组原值，由本引擎算完限伤后自行扣血**：
 * <ul>
 *   <li><b>hurt 路径</b>（{@code modifyHurtAmount}）：原始 amount 经 单发限 cap 得放行量，
 *       原版 subsequently 扣血（setHealth(getHealth()-放行量)）。</li>
 *   <li><b>setHealth 路径</b>（{@code modifyHealthForVitalitySeverance}）：外部模组直接 setHealth 的
 *       目标值被丢弃，扣血方向按「当前血量 − 限伤后变化量」自行设血；治疗方向放行。
 *       <b>永不豁免</b>（无"已限过"标记）——外部模组在一次攻击里先 hurt() 再直接 setHealth(0)，
 *       若 setHealth 被"已限过"豁免就会一刀秒；必须每次 setHealth 都无条件过限伤。</li>
 *   <li><b>不豁免任何来源</b>（kill / 环境伤害 / 模组直改一律限伤）。</li>
 * </ul></p>
 *
 * <p><b>CD = 无敌帧时间</b>（天然实现，不设独立 CD 计时器）：结算一次伤害后
 * {@link YizAttributes#INVINCIBILITY_MULT} 无敌帧（onHurtSuccess 在 hurt RETURN 激活 N tick 完全无敌）
 * 天然挡掉 CD 内后续伤害（onHurtPre cancel）；无敌帧到期后下一次伤害再被结算 → 闭环。</p>
 *
 * <p><b>减伤分工</b>：DAMAGE_REDUCTION（百分比）+ DAMAGE_BLOCK（固定）——
 * 非闭包实体在 setHealth 层（modifyHealthForVitalitySeverance 扣血分支）已做，此处不重复；
 * 仅对血量闭包实体（{@link SecureHealthClosure#isSecure}，setHealth 层短路跳过这两项减伤）在引擎内补。</p>
 *
 * <p><b>限伤</b>：{@code applied = min(衰减后, max(3, maxHealth × CONDUCTION_CAP/100))}
 * （值 = 最大生命值百分比）。双重限伤无害：hurt 层与 setHealth 层都 min 到同一 cap，不会更小。</p>
 *
 * <p>1.20.1 差异：{@code entity.level()} → {@code entity.level}（字段）；属性用
 * {@code YizAttributes.*.get()}（RegistryObject）传给 {@code getAttribute(Attribute)}，不传 Holder。</p>
 */
public final class ConductionDamageLimiter {

    private ConductionDamageLimiter() {}

    /** 血量闭包实体保底单发限伤上限（最大生命值百分比，属性未挂/为 0 时兜底）。 */
    private static final double DEFAULT_SECURE_CAP_PERCENT = 25.0;

    // ==================== 伤害记录器 ====================

    /** 一条伤害记录（hurt 路径 / sethealth 路径）。 */
    public record DamageRecord(String path, long tick, String source, float raw, float reduced, float cap, float applied) {}

    /** 实体 UUID → 最近伤害记录（FIFO，上限 64 条）。 */
    private static final Map<UUID, Deque<DamageRecord>> JOURNAL = new ConcurrentHashMap<>();
    private static final int JOURNAL_MAX = 64;

    private static void record(String path, LivingEntity target, DamageSource source, float raw, float reduced, float cap, float applied) {
        try {
            UUID uuid = target.getUUID();
            String srcName = source != null && source.getEntity() != null
                    ? source.getEntity().getClass().getSimpleName() : (source != null ? source.getMsgId() : "unknown");
            Deque<DamageRecord> q = JOURNAL.computeIfAbsent(uuid, k -> new ArrayDeque<>());
            synchronized (q) {
                q.addLast(new DamageRecord(path, target.level().getGameTime(), srcName, raw, reduced, cap, applied));
                while (q.size() > JOURNAL_MAX) q.removeFirst();
            }
        } catch (Throwable ignored) {}
    }

    /** 查询实体最近伤害记录（最近 64 条，FIFO；无则空列表）。 */
    public static java.util.List<DamageRecord> getRecords(LivingEntity target) {
        Deque<DamageRecord> q = JOURNAL.get(target.getUUID());
        if (q == null) return java.util.List.of();
        synchronized (q) {
            return new java.util.ArrayList<>(q);
        }
    }

    /** 清空实体的伤害记录。 */
    public static void clearRecords(LivingEntity target) {
        JOURNAL.remove(target.getUUID());
    }

    // ==================== hurt 路径限伤 ====================

    /**
     * modifyHurtAmount 尾部：对 amount 应用「单发限 cap」，返回放行量。
     * <p>不做 DAMAGE_REDUCTION/BLOCK 减伤（由 setHealth 层统一做），只限 cap。
     * 不豁免任何来源（kill/环境伤害一律限）。被 onHurtPre cancel（无敌帧 CD）的伤害不会走到此处。</p>
     */
    public static float limitHurt(LivingEntity target, DamageSource source, float amount, long gameTick) {
        if (target.level().isClientSide()) return amount;
        if (amount <= 0) return amount;

        double cap = readAttr(target, YizAttributes.CONDUCTION_CAP.get());
        // 保底限伤（写死不依赖属性）：血量闭包实体即使属性未挂/为 0 也默认限 25% 上限，
        // 防止外部模组（setHealth(0)/巨量伤害）因属性挂载失败而穿透秒杀。
        boolean isSecure = SecureHealthClosure.isSecure(target);
        if (cap <= 0) {
            if (!isSecure) return amount; // 非闭包实体无传导限伤 → 放行
            cap = DEFAULT_SECURE_CAP_PERCENT; // secure 实体保底 25%
        }

        float raw = amount;
        float limit = dynamicCap(target, cap);
        float applied = Math.min(raw, limit);

        record("hurt", target, source, raw, raw, limit, applied);
        return applied;
    }

    // ==================== setHealth 路径限伤 ====================

    /**
     * setHealth HEAD：外部直接 setHealth 的目标值被丢弃，扣血方向自行限伤设血。
     * <ul>
     *   <li>扣血方向（newHealth &lt; current）→ 变化量 delta 经 减伤（仅闭包实体）→限 cap，
     *       返回 {@code current - 限伤后}。**永不豁免**（无"已限过"标记）——每次 setHealth 都无条件限。</li>
     *   <li>治疗方向（newHealth ≥ current）→ 放行（治疗不限伤）。</li>
     * </ul>
     */
    public static float limitSetHealth(LivingEntity target, float newHealth, long gameTick) {
        if (target.level().isClientSide()) return newHealth;

        double cap = readAttr(target, YizAttributes.CONDUCTION_CAP.get());
        float current = target.getHealth();
        if (newHealth >= current) return newHealth; // 治疗放行

        // 保底限伤：血量闭包实体即使属性未挂/为 0 也默认限 25%，防外部 setHealth(0) 秒杀
        boolean isSecure = SecureHealthClosure.isSecure(target);
        if (cap <= 0) {
            if (!isSecure) return newHealth; // 非闭包实体无传导限伤 → 放行
            cap = DEFAULT_SECURE_CAP_PERCENT;
        }

        float raw = current - newHealth; // 扣血方向变化量
        float reduced = applyReductionsIfSecure(target, raw);
        float limit = dynamicCap(target, cap);
        float applied = Math.min(reduced, limit);

        float next = Math.max(0, current - applied);
        record("sethealth", target, null, raw, reduced, limit, applied);
        return next;
    }

    // ==================== tick / 清理 ====================

    /** 每 tick：清理死亡/卸载实体状态。由 {@code LivingEntityMixin.onTick}（服务端分支）调用。 */
    public static void tick(LivingEntity target) {
        if (!target.isAlive()) removeAll(target);
    }

    /** 实体死亡/移除时清理。由 {@code LivingEntityMixin.onDie} 调用。 */
    public static void removeAll(LivingEntity target) {
        JOURNAL.remove(target.getUUID());
    }

    // ==================== 工具 ====================

    /** 读属性值（null 安全）。1.20.1 用 RegistryObject.get() 取得 Attribute 直传 getAttribute。 */
    private static double readAttr(LivingEntity entity, Attribute attr) {
        var inst = entity.getAttribute(attr);
        return inst != null ? inst.getValue() : 0;
    }

    /** 减伤（仅对血量闭包实体补——其 setHealth 层短路跳过 DAMAGE_REDUCTION/BLOCK；非闭包实体 setHealth 层已做）。 */
    private static float applyReductionsIfSecure(LivingEntity target, float amount) {
        if (!SecureHealthClosure.isSecure(target)) return amount;
        double red = readAttr(target, YizAttributes.DAMAGE_REDUCTION.get());
        if (red > 0) amount *= (float) (1.0 - Math.min(1.0, red / 100.0));
        double block = readAttr(target, YizAttributes.DAMAGE_BLOCK.get());
        if (block > 0) amount = Math.max(0, amount - (float) block);
        return amount;
    }

    /** 限伤上限：{@code max(3, maxHealth × value/100)}（值 = 最大生命值百分比）。 */
    private static float dynamicCap(LivingEntity target, double value) {
        float maxHp = target.getMaxHealth();
        if (maxHp <= 0) return (float) value;
        return Math.max(3.0f, (float) (maxHp * value / 100.0));
    }
}
