package net.minecraft.client.yiz.tool.chess;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.client.yiz.attribute.YizAttributes;
import net.minecraft.client.yiz.tool.attribute.EntityAttributeGate;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 自走棋棋子单位外部表（服务端权威）— 星级/费用/归属统一存储 + 星级属性应用。
 *
 * <p>设计（用户确认 2026-08-29）：
 * <ul>
 *   <li>星级存外部表（权威），实体 DataParameter 镜像同步给客户端（描边/体型渲染用）。</li>
 *   <li>1 星基准 = 实体生成时的现有属性（Boss 模板），升星只放大随倍率 3 项：
 *       生命值 / 攻击力 / 每秒回血，其余固定。</li>
 *   <li>倍率表：1-3费 ×1/×1.5/×2.25；4费 ×1/×1.5/×3；5费 ×1/×2/×6；7费 ×1/×2.5/×9。
 *       辖界者=5费档、踏虚体/邪狱龙=7费档（用户指定）。</li>
 * </ul>
 * 持久化仿 {@link net.minecraft.client.yiz.tool.effect.InstanceEffectState}（NBT yiz_chess，实体 save 回调调用）。
 */
public final class ChessUnitTable {

    private static final String TAG_KEY = "yiz_chess";
    /** 回血 modifier idKey：与辖界者 applyEntityAttributes 的 setAttr("life_regen_rate") 同 key，
     *  EntityAttributeGate.set 会 remove+add 覆盖而非叠加。 */
    private static final String REGEN_ID_KEY = "life_regen_rate";

    private static final ConcurrentHashMap<UUID, Entry> UNITS = new ConcurrentHashMap<>();

    /** 单棋子状态：费用 + 星级 + 归属 + 1 星基准值（生成时快照）。 */
    public static final class Entry {
        public volatile int cost = 1;
        public volatile int star = 1;
        public volatile UUID owner;
        /** 1 星基准：MAX_HEALTH/ATTACK_DAMAGE base 值 + LIFE_REGEN_RATE 值（init 时快照）。 */
        public volatile double baseHealth = -1;
        public volatile double baseAttack = -1;
        public volatile double baseRegen = 0;
    }

    private ChessUnitTable() {}

    // ==================== 倍率表 ====================

    /** 升星倍率：1-3费 ×1/×1.5/×2.25；4费 ×1/×1.5/×3；5费 ×1/×2/×6；7费 ×1/×2.5/×9。 */
    public static double multiplier(int cost, int star) {
        double[] m = switch (cost) {
            case 4 -> new double[]{1, 1.5, 3};
            case 5 -> new double[]{1, 2, 6};
            case 7 -> new double[]{1, 2.5, 9};
            default -> new double[]{1, 1.5, 2.25};
        };
        int idx = Math.max(1, Math.min(3, star)) - 1;
        return m[idx];
    }

    // ==================== 读写 ====================

    /** 首次标记棋子：记录费用/星级。1 星基准延迟到 {@link #applyStar} 首次调用时快照
     *  （等 applyEntityAttributes 跑完，含辖界者难度缩放）。 */
    public static Entry init(LivingEntity entity, int cost, int star) {
        if (entity == null) return null;
        Entry e = UNITS.computeIfAbsent(entity.getUUID(), u -> new Entry());
        e.cost = cost;
        e.star = Math.max(1, Math.min(3, star));
        return e;
    }

    public static Entry get(LivingEntity entity) {
        return entity == null ? null : UNITS.get(entity.getUUID());
    }

    public static int getStar(LivingEntity entity) {
        Entry e = get(entity);
        return e == null ? 1 : e.star;
    }

    public static int getCost(LivingEntity entity) {
        Entry e = get(entity);
        return e == null ? 1 : e.cost;
    }

    /** 设星级 + 应用星级属性（外部表权威，调用方负责同步 DataParameter 镜像）。 */
    public static void setStar(LivingEntity entity, int star) {
        Entry e = get(entity);
        if (e == null) return;
        e.star = Math.max(1, Math.min(3, star));
        applyStar(entity, e);
    }

    /** 设归属玩家（招聘模型）。 */
    public static void setOwner(LivingEntity entity, UUID ownerUuid) {
        Entry e = get(entity);
        if (e == null) return;
        e.owner = ownerUuid;
    }

    public static UUID getOwner(LivingEntity entity) {
        Entry e = get(entity);
        return e == null ? null : e.owner;
    }

    /** 实体移除/真实死亡时清理（防 UUID 复用残留）。 */
    public static void remove(UUID uuid) {
        if (uuid != null) UNITS.remove(uuid);
    }

    // ==================== 星级属性应用 ====================

    /**
     * 按费用档 + 星级应用属性：随倍率 3 项（生命值/攻击力/每秒回血） = 1 星基准 × 倍率。
     * 其余属性（护甲/法防/减伤/格挡/限伤/移速等）星级无关，保持 1 星值。
     */
    public static void applyStar(LivingEntity entity, Entry e) {
        if (entity == null || e == null) return;
        // 首次应用：快照当前属性为 1 星基准（此时 applyEntityAttributes 已完成）
        if (e.baseHealth < 0) snapshotBase(entity, e);
        double mult = multiplier(e.cost, e.star);
        double newHealth = -1;
        AttributeInstance hp = entity.getAttribute(Attributes.MAX_HEALTH);
        if (hp != null && e.baseHealth >= 0) {
            newHealth = e.baseHealth * mult;
            hp.setBaseValue(newHealth);
        }
        AttributeInstance atk = entity.getAttribute(Attributes.ATTACK_DAMAGE);
        if (atk != null && e.baseAttack >= 0) {
            atk.setBaseValue(e.baseAttack * mult);
        }
        double regenVal = 0;
        if (e.baseRegen > 0 && YizAttributes.LIFE_REGEN_RATE.isPresent()) {
            regenVal = e.baseRegen * mult;
            EntityAttributeGate.set(entity, YizAttributes.LIFE_REGEN_RATE, REGEN_ID_KEY, regenVal);
        }
        // 星级放大后同步两处守卫标准（否则星级值会被还原回 1 星基准）：
        // 1) SecureHealthClosure 权威最大生命值表（getMaxHealth 优先读表，只改 vanilla base 血条不更新）
        if (newHealth > 0) {
            net.minecraft.client.yiz.tool.health.SecureHealthClosure.setMaxHealth(entity, (float) newHealth);
            // 当前血量同步到满血：maxHealth 放大后当前血量仍是 1 星满血（400/2400 残血比例），
            // 星级蛋生成的新棋子应出生满血
            net.minecraft.client.yiz.tool.health.SecureHealthClosure.setHealth(entity, (float) newHealth);
        }
        // 2) AttributeStandardizer 每 20 tick 审计（实体 applyEntityAttributes 注册过标准，
        //    星级值 ≠ 标准值会被判定「外部篡改」还原；必须把标准更新为当前星级值）
        net.minecraft.client.yiz.tool.attribute.AttributeStandardizer.registerStandard(entity,
            Attributes.MAX_HEALTH, "max_health", 0);
        if (YizAttributes.LIFE_REGEN_RATE.isPresent()) {
            net.minecraft.client.yiz.tool.attribute.AttributeStandardizer.registerStandard(entity,
                YizAttributes.LIFE_REGEN_RATE.get(), REGEN_ID_KEY, regenVal);
        }
    }

    /** 快照实体当前属性为 1 星基准（生命值/攻击力 base + 每秒回血值）。 */
    private static void snapshotBase(LivingEntity entity, Entry e) {
        AttributeInstance hp = entity.getAttribute(Attributes.MAX_HEALTH);
        AttributeInstance atk = entity.getAttribute(Attributes.ATTACK_DAMAGE);
        e.baseHealth = hp != null ? hp.getBaseValue() : -1;
        e.baseAttack = atk != null ? atk.getBaseValue() : -1;
        if (YizAttributes.LIFE_REGEN_RATE != null && YizAttributes.LIFE_REGEN_RATE.isPresent()) {
            AttributeInstance regen = entity.getAttribute(YizAttributes.LIFE_REGEN_RATE.get());
            e.baseRegen = regen != null ? regen.getValue() : 0;
        }
    }

    // ==================== 持久化 ====================

    /** 序列化棋子态（cost/star/owner + 1 星基准）到 NBT（实体 addAdditionalSaveData 调用）。 */
    public static void writeState(LivingEntity entity, CompoundTag tag) {
        if (entity == null || tag == null) return;
        Entry e = UNITS.get(entity.getUUID());
        if (e == null) return;
        CompoundTag state = new CompoundTag();
        state.putInt("cost", e.cost);
        state.putInt("star", e.star);
        if (e.owner != null) state.putUUID("owner", e.owner);
        state.putDouble("baseHealth", e.baseHealth);
        state.putDouble("baseAttack", e.baseAttack);
        state.putDouble("baseRegen", e.baseRegen);
        tag.put(TAG_KEY, state);
    }

    /** 从 NBT 恢复棋子态（实体 readAdditionalSaveData 调用）。 */
    public static void readState(LivingEntity entity, CompoundTag tag) {
        if (entity == null || tag == null) return;
        if (!tag.contains(TAG_KEY, Tag.TAG_COMPOUND)) return;
        CompoundTag state = tag.getCompound(TAG_KEY);
        Entry e = UNITS.computeIfAbsent(entity.getUUID(), u -> new Entry());
        e.cost = state.getInt("cost");
        e.star = state.getInt("star");
        e.owner = state.contains("owner") ? state.getUUID("owner") : null;
        e.baseHealth = state.contains("baseHealth") ? state.getDouble("baseHealth") : -1;
        e.baseAttack = state.contains("baseAttack") ? state.getDouble("baseAttack") : -1;
        e.baseRegen = state.contains("baseRegen") ? state.getDouble("baseRegen") : 0;
    }
}
