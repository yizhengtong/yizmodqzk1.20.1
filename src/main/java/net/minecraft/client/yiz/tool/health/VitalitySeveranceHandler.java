package net.minecraft.client.yiz.tool.health;

import net.minecraft.world.entity.LivingEntity;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 绝妄生机（原禁疗）处理器 — 1.20.1 移植版。
 *
 * <p>本次只移植辖界者用到的叠加式绝妄生机（{@link #addStackingBan} /
 * {@link #getStackingPercent}）。完整的禁疗三层机制（永久配置/临时/属性驱动）后续按需补。</p>
 */
public final class VitalitySeveranceHandler {

    private VitalitySeveranceHandler() {}

    /** 临时禁疗条目。 */
    private record BanEntry(float banFactor, long expiryTick) {}

    private static final Map<UUID, BanEntry> BANS = new ConcurrentHashMap<>();

    /** 施加临时禁疗：factor 0~1（1=完全禁疗），duration tick。 */
    public static void addTempBan(LivingEntity entity, float factor, long duration) {
        long expiry = entity.level().getGameTime() + duration;
        BANS.put(entity.getUUID(), new BanEntry(Math.min(1.0f, Math.max(0, factor)), expiry));
    }

    /** 获取实体临时禁疗系数（0~1）；过期自动清除。 */
    public static float getBanFactor(LivingEntity entity) {
        BanEntry entry = BANS.get(entity.getUUID());
        if (entry == null) return 0;
        if (entity.level().getGameTime() >= entry.expiryTick) {
            BANS.remove(entity.getUUID());
            return 0;
        }
        return entry.banFactor;
    }

    /** 移除实体临时禁疗。 */
    public static void removeTempBan(LivingEntity entity) {
        BANS.remove(entity.getUUID());
    }

    /** 叠加式绝妄生机条目。 */
    private record StackEntry(float percent, long expiryTick) {}

    private static final Map<UUID, StackEntry> STACK_BANS = new ConcurrentHashMap<>();

    /**
     * 叠加式绝妄生机：在现有基础上 +deltaPercent（上限 100%），并重置持续时长。
     * 连续攻击持续叠加（辖界者每次攻击 +5% → 最高 100% = 完全禁疗）。
     */
    public static void addStackingBan(LivingEntity entity, float deltaPercent, long duration) {
        StackEntry prev = STACK_BANS.get(entity.getUUID());
        float next = Math.min(100.0f, (prev != null ? prev.percent : 0.0f) + deltaPercent);
        STACK_BANS.put(entity.getUUID(), new StackEntry(next, entity.level().getGameTime() + duration));
    }

    /** 获取叠加式绝妄生机当前百分比（0~100）；过期自动清除。 */
    public static float getStackingPercent(LivingEntity entity) {
        StackEntry e = STACK_BANS.get(entity.getUUID());
        if (e == null) return 0;
        if (entity.level().getGameTime() >= e.expiryTick) {
            STACK_BANS.remove(entity.getUUID());
            return 0;
        }
        return e.percent;
    }

    /** 实体下线/移除时清理。 */
    public static void clear(LivingEntity entity) {
        STACK_BANS.remove(entity.getUUID());
    }

    //  通道级禁疗强制（Float DataParameter 盲区）

    private static final Map<UUID, Map<Integer, Float>> CHANNEL_SNAPSHOTS = new ConcurrentHashMap<>();

    /**
     * 周期性禁疗强制：检测实体各 Float 通道是否出现未经授权的增长，按禁疗配置削减。
     * 由实体每 ~10 tick 调用一次。
     */
    public static void enforceTick(LivingEntity entity) {
        var config = VitalitySeveranceConfig.get(entity);
        if (config == null) {
            CHANNEL_SNAPSHOTS.remove(entity.getUUID());
            return;
        }

        Map<Integer, Float> prev = CHANNEL_SNAPSHOTS.get(entity.getUUID());
        Map<Integer, Float> current = new HashMap<>();

        DirectHealthFallback.forEachFloatItem(entity, (accessor, value, item) -> {
            current.put(accessor.getId(), value);
        });

        if (prev != null) {
            for (Map.Entry<Integer, Float> entry : current.entrySet()) {
                Integer id = entry.getKey();
                float now = entry.getValue();
                Float was = prev.get(id);
                if (was != null && now > was + 0.01f) {
                    float increase = now - was;
                    float allowed = config.apply(increase);
                    float banned = increase - Math.max(0, allowed);
                    if (banned > 0.01f) {
                        DirectHealthFallback.forEachFloatItem(entity, (acc, v, item) -> {
                            if (acc.getId() == id) {
                                item.setValue(v - banned);
                                item.setDirty(true);
                            }
                        });
                        current.put(id, now - banned);
                    }
                }
            }
        }
        CHANNEL_SNAPSHOTS.put(entity.getUUID(), current);
    }

    /** 更新通道快照基线（modifyHealth/addDelta/setHealth 主动改血后调用，防强制拦截）。 */
    public static void updateBaseline(LivingEntity entity) {
        if (VitalitySeveranceConfig.get(entity) == null) return;
        Map<Integer, Float> snap = new HashMap<>();
        DirectHealthFallback.forEachFloatItem(entity, (accessor, value, item) -> {
            snap.put(accessor.getId(), value);
        });
        CHANNEL_SNAPSHOTS.put(entity.getUUID(), snap);
    }

    //  字段级绝妄生机强制（配合 EntityHealthLocator 真实血量字段）

    /** 定位真实血量字段的基线快照，检测回弹用。 */
    private static final Map<UUID, Double> FIELD_SNAPSHOTS = new ConcurrentHashMap<>();

    /**
     * 字段级绝妄生机强制：对已绝妄生机目标，用 {@link EntityHealthLocator} 定位真实血量字段，
     * 检测「回血方向」变化（inverse 型字段减少 / 正向型字段增加 = 回血）→ 反射写回基线抵消。
     * 由实体每 ~10 tick 与 enforceTick 一起调用。
     */
    public static void enforceFieldTick(LivingEntity entity) {
        var config = VitalitySeveranceConfig.get(entity);
        if (config == null) {
            FIELD_SNAPSHOTS.remove(entity.getUUID());
            return;
        }
        var slot = EntityHealthLocator.locate(entity);
        if (slot == null) {
            FIELD_SNAPSHOTS.remove(entity.getUUID());
            return;
        }
        Double cur = EntityHealthLocator.readLocated(entity);
        if (cur == null) return;
        Double prev = FIELD_SNAPSHOTS.get(entity.getUUID());
        if (prev != null) {
            boolean healed = slot.inverse() ? (cur < prev) : (cur > prev);
            if (healed) {
                EntityHealthLocator.writeLocated(entity, prev);
                cur = prev;
            }
        }
        FIELD_SNAPSHOTS.put(entity.getUUID(), cur);
    }

    /**
     * 更新字段基线（本模组主动扣血 {@link EntityHealthLocator#applyPersistentDamage} 成功后调用，
     * 防止把这次扣血当成「回弹」抵消回去）。
     */
    public static void updateFieldBaseline(LivingEntity entity) {
        if (!FIELD_SNAPSHOTS.containsKey(entity.getUUID())) return;
        Double cur = EntityHealthLocator.readLocated(entity);
        if (cur != null) FIELD_SNAPSHOTS.put(entity.getUUID(), cur);
    }
}
