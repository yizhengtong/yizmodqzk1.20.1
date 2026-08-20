package net.minecraft.client.yiz.tool.health;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 持续血量钉压（纯减法语义 / 模式 G8「每 tick 权威回写」的通用对策）。
 *
 * <p>部分模组实体的真实血量槽虽然可写，但模组每 tick 的权威程序会按
 * 「单次扣血上限 + 血线下限 + 回血」重新断言槽值（路西法型：每 tick 上限 5%、
 * 下限 10、慢回血）。单次写入会被下个 tick 拉回，减法结果无法保持。</p>
 *
 * <p>本类对「定位成功的梦魇目标」注册每 tick 重写任务：每个 tick 把槽重写到
 * <b>本次命中后的精确目标值</b>（纯减法结果），压制模组权威拉回与回血——
 * 1000 − 500 = 500 就保持 500，再 − 500 = 0 就保持 0。</p>
 *
 * <p>任务只认「移除/客户端/战斗冷却」释放，不因 {@code isAlive/isDeadOrDying}
 * 提前释放（目标被压到 0 时这两者会为 false，提前释放会让模组立刻回血）。
 * 若目标被压在 ≤2% 上限超过 2 秒仍未死（模组拒绝血量死亡），升级为合成死亡链
 * （{@link EntityASMUtil#dreamDeathblow}）。</p>
 *
 * <p>全类型特征驱动，不引用任何目标模组类名/字段名。</p>
 */
public final class SustainedHealthSuppression {

    private static final String TASK_ID = "dream-suppress";
    /** 最后一次梦魇命中后维持压制的时长（超出释放，防永久压制）。 */
    private static final long HIT_TTL_MS = 6000L;
    /** 血线冻结（≤2% 上限）持续 tick 数 → 升级合成死亡链。 */
    private static final int PINNED_ESCALATE_TICKS = 40;
    private static final double PINNED_RATIO = 0.02;

    /** uuid → 压制条目（目标值 + 最近攻击者，供死亡链归属）。 */
    private static final Map<UUID, Entry> ENTRIES = new ConcurrentHashMap<>();
    /** uuid → 血线冻结连续 tick 数。 */
    private static final Map<UUID, Integer> PINNED = new ConcurrentHashMap<>();

    private SustainedHealthSuppression() {}

    private record Entry(LivingEntity target, LivingEntity attacker, float targetHealth, long lastHitMs) {}

    /**
     * 记录一次梦魇命中：注册/刷新该目标的持续钉压任务（幂等，不叠加）。
     *
     * @param targetHealth 本次命中后的精确目标血量（纯减法结果，任务将保持该值）
     */
    public static void hit(LivingEntity target, LivingEntity attacker, float targetHealth) {
        if (target == null || target.level().isClientSide()) return;
        if (target instanceof Player) return;   // 玩家走原死亡链，不持续压制
        UUID uuid = target.getUUID();
        ENTRIES.put(uuid, new Entry(target, attacker, Math.max(0, targetHealth), System.currentTimeMillis()));
        HealthModificationScheduler.remove(target, TASK_ID);
        HealthModificationScheduler.schedule(target,
            HealthModificationScheduler.repeating(TASK_ID, 1, 1, SustainedHealthSuppression::tick));
        if (HIT_LOG.add(uuid)) {
            LOGGER.info("[Suppress] 注册持续钉压 {} ({}) 目标血={} attacker={}",
                target.getClass().getName(), target.getId(), targetHealth,
                attacker == null ? "null" : attacker.getClass().getSimpleName());
        }
    }

    /** 释放目标（移除/客户端/离开战斗）。 */
    public static void release(LivingEntity target) {
        if (target == null) return;
        ENTRIES.remove(target.getUUID());
        PINNED.remove(target.getUUID());
        HealthModificationScheduler.remove(target, TASK_ID);
    }

    /** 每 tick 执行：把槽重写到目标值 + 血线冻结检测。 */
    private static void tick(LivingEntity entity) {
        Entry entry = ENTRIES.get(entity.getUUID());
        if (entry == null) {
            release(entity);
            return;
        }
        // 只认移除/客户端释放：目标被压到 0 时 isAlive/isDeadOrDying 会为 false，
        // 若据此释放 → 模组立刻回血，减法结果无法保持
        if (entity.isRemoved() || entity.level().isClientSide()) {
            release(entity);
            return;
        }
        // 战斗冷却：超出 TTL 无命中 → 释放（防永久压制）
        if (System.currentTimeMillis() - entry.lastHitMs() > HIT_TTL_MS) {
            LOGGER.info("[Suppress] 释放(战斗冷却) {} ({})", entity.getClass().getName(), entity.getId());
            release(entity);
            return;
        }
        // 每 tick 重写槽 → 目标值（纯减法语义：压制模组每 tick 权威回写/回血/上限）
        try {
            if (EntityHealthLocator.locate(entity) != null) {
                Double cur = EntityHealthLocator.readLocated(entity);
                if (cur != null && Math.abs(cur - entry.targetHealth()) > 0.5) {
                    EntityHealthLocator.writeLocated(entity, entry.targetHealth());
                    if (entity.tickCount % 20 == 0) {
                        LOGGER.info("[Suppress] tick {} ({}) 槽值 {} → 目标 {}", entity.getClass().getName(),
                            entity.getId(), cur, entry.targetHealth());
                    }
                }
            }
        } catch (Throwable ignored) {}

        // 血线冻结检测：被压到 ≤2% 上限且长时间未死 → 模组拒绝血量死亡 → 升级合成死亡链
        try {
            float maxHp = entity.getMaxHealth();
            if (maxHp > 0 && entity.getHealth() <= Math.max(1.0f, maxHp * (float) PINNED_RATIO)) {
                int pinned = PINNED.getOrDefault(entity.getUUID(), 0) + 1;
                if (pinned == 20 || pinned == PINNED_ESCALATE_TICKS) {
                    LOGGER.info("[Suppress] 血线冻结 {} ({}) pinned={}/{}", entity.getClass().getName(),
                        entity.getId(), pinned, PINNED_ESCALATE_TICKS);
                }
                if (pinned >= PINNED_ESCALATE_TICKS) {
                    PINNED.remove(entity.getUUID());
                    release(entity);
                    // 允许死亡链 +25 tick 后强制深层移除（复活型实体每 tick 重置 dead）
                    EntityASMUtil.markForceRemoveAllowed(entity.getId());
                    LOGGER.warn("[Suppress] 升级合成死亡链 {} ({})", entity.getClass().getName(), entity.getId());
                    EntityASMUtil.dreamDeathblow(entry.attacker(), entity);
                    return;
                }
                PINNED.put(entity.getUUID(), pinned);
            } else {
                PINNED.remove(entity.getUUID());
            }
        } catch (Throwable ignored) {}
    }

    private static final org.slf4j.Logger LOGGER = net.minecraft.client.yiz.tizMod.LOGGER;
    private static final java.util.Set<UUID> HIT_LOG = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 死亡清理入口（由 die 处理器调用，防 ENTRIES 泄漏）。 */
    public static void remove(LivingEntity entity) {
        release(entity);
    }
}
