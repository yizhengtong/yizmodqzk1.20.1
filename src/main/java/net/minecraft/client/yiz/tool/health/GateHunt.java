package net.minecraft.client.yiz.tool.health;

import net.minecraft.world.entity.LivingEntity;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 权威门控猎杀（P0.5 全量直改的写回对抗层）。
 *
 * <p>全量直改后若值被模组每 tick 权威程序拉回（扣血上限/血线下限/回血），本类做两件事：</p>
 * <ol>
 *   <li><b>写回验证</b>：命中后 2 tick 复查槽值是否仍为目标值；</li>
 *   <li><b>门控猎杀</b>：若被拉回，逐个试探布尔候选（NBT 布尔 → Boolean
 *       DataParameter → boolean 字段），翻转 + 重写目标，2 tick 后若值钉住
 *       则该布尔就是权威门控 → 保持翻转（击穿），按类缓存。</li>
 * </ol>
 *
 * <p>只在目标被压到 0 时触发；候选逐个试探、每次试探都还原未命中项；结果按类缓存
 * （同类只猎一次）。全行为驱动，不引用任何目标模组字段名。</p>
 */
public final class GateHunt {

    private static final int MAX_CANDIDATES = 8;
    private static final double STICK_TOLERANCE = 1.0;

    /** uuid → 正在猎杀（防重入）。 */
    private static final java.util.Set<UUID> HUNTING = ConcurrentHashMap.newKeySet();
    /** 类名 → 已确认门控（命中后保持翻转）。 */
    private static final Map<String, String> FOUND_GATE = new ConcurrentHashMap<>();
    /** 类名 → 已确认无门控（负缓存）。 */
    private static final java.util.Set<String> NEGATIVE = ConcurrentHashMap.newKeySet();
    /** 候选 dump 诊断（每类一次）。 */
    private static final java.util.Set<String> DIAG = ConcurrentHashMap.newKeySet();
    /** uuid → 反向累加器槽已重写钉回的次数（有上限，用尽后才退回门控猎杀）。 */
    private static final Map<UUID, Integer> PIN_TRIES = new ConcurrentHashMap<>();
    /** 已确认门控候选的原始值（key = 类名#候选描述）——死亡/失效时还原，避免把无关字段永久钉在 1e9。 */
    private static final Map<String, Double> PIN_ORIGINALS = new ConcurrentHashMap<>();

    private static final int MAX_PIN_TRIES = 6;

    private GateHunt() {}

    /** 全量直改后调用：2 tick 后验证写回，被拉回则钉回反向槽 / 启动门控猎杀。 */
    public static void verifyAndHunt(LivingEntity entity, double target) {
        if (entity == null || entity.level().isClientSide() || entity.isRemoved()) return;
        // 每一刀都验证（不只在 target=0）：有「每 tick 权威拉回」的实体，中段伤害也会被拉回，
        // 需要提前猎杀门控，否则规则 A 的直接减法永远被扣血上限挡住。
        // 不用 isDeadOrDying() 提前返回——「血打 0 瞬间 isDeadOrDying 翻 true、下一 tick 又复活」的
        // 实体会因此漏猎。是否真死交给 2 tick 验证里的 isRemoved 判断。
        UUID uuid = entity.getUUID();
        if (HUNTING.contains(uuid)) return;
        String cls = entity.getClass().getName();
        // 反向（累加器）槽实体：血量权威就是一个字段，第三方回血也改同一个字段 ⇒「把值钉回去」
        // 这条路径每次都该跑，不能被 FOUND_GATE/NEGATIVE 短路（那两个缓存说的是"门控猎过了"，
        // 与"值要不要钉回去"无关）。
        boolean pinCapable = EntityHealthLocator.isInverseLocatedSlot(entity);
        if (!pinCapable && (FOUND_GATE.containsKey(cls) || NEGATIVE.contains(cls))) return;   // 已缓存
        HealthModificationScheduler.remove(entity, "gate-verify");
        HealthModificationScheduler.schedule(entity,
            HealthModificationScheduler.once("gate-verify", 2, e -> {
                if (e == null) return;
                if (e.isRemoved()) {
                    restorePinned(e);   // 实体没了 → 还原被钉住的候选字段
                    // 写回验证的「死亡」分支：本次写入已经通过立即回读（apply 里回读没过就直接返回，
                    // 不会走到 verifyAndHunt），实体又已被它打死 ⇒「写进去 + 没被拉回」成立
                    // → 这个类可以按常规实体缓存（非常规判定粘性优先，不会被这里覆盖）。
                    HealthTier.markRegular(e.getClass(), "2 tick 写回验证：实体已被本次写入击杀");
                    return;
                }
                double now = readLogical(e);
                if (!Double.isFinite(now)) return;
                // 容差收紧（原为 target*5%，target=377 时高达 18.8 → 差 14 点也被判成"写回保持"，
                // 既漏掉真实的拉回、又让后续门控验收被"我们自己上一笔写入"糊弄过去）
                double tol = Math.max(0.5, Math.abs(target) * 0.01);
                // 只在「血被向上拉回」时处理（now > target + tol）；向下偏离可能是并发伤害，不动。
                if (now > target + tol) {
                    // 被拉回 = 有权威程序在回写血量 ⇒ 非常规生命值实体：此后每次攻击都现场全量扫描
                    HealthTier.markIrregular(e.getClass(), "2 tick 写回被拉回（有权威程序回写血量）");
                    if (pinCapable) {
                        int tries = PIN_TRIES.merge(uuid, 1, Integer::sum);
                        if (tries <= MAX_PIN_TRIES && EntityHealthLocator.reassertLocatedSlot(e, target)) {
                            // 反向槽：回写与第三方回血落在同一个字段上 ⇒ 直接重写钉回，
                            // 比去猎门控更直接，也不会误伤无关字段（曾把某个 cooldown 钉成 1e9）。
                            LOGGER.warn("[GateHunt] {} 反向累加器槽被拉回 当前={} 目标={} → 重写钉回（第 {}/{} 次）",
                                cls, now, target, tries, MAX_PIN_TRIES);
                            verifyAndHunt(e, target);
                            return;
                        }
                        PIN_TRIES.remove(uuid);
                    }
                    LOGGER.warn("[GateHunt] {} 写回被拉回 当前={} 目标={} → 启动门控猎杀",
                        cls, now, target);
                    hunt(e, target, cls, uuid);
                } else {
                    PIN_TRIES.remove(uuid);
                    // 值钉住了 = 「写进去 + 2 tick 内没被拉回」的行为证明 ⇒ 按常规实体缓存
                    HealthTier.markRegular(e.getClass(), "2 tick 写回保持（无权威对抗）");
                    LOGGER.info("[GateHunt] {} 写回保持 当前={} 目标={}（无权威对抗）", cls, now, target);
                }
            }));
    }

    /** 门控候选的钉住/还原簿记（key = 类名#候选描述）。 */
    private static void rememberPin(String key, double original) {
        PIN_ORIGINALS.putIfAbsent(key, original);
    }

    /** 还原该实体类所有被钉住的候选字段（实体死亡/移除或门控判定失效时调用）。 */
    private static void restorePinned(LivingEntity entity) {
        if (entity == null) return;
        String cls = entity.getClass().getName();
        try {
            for (BoolRef c : BoolRef.candidates(entity)) {
                String k = cls + "#" + c.describe();
                Double orig = PIN_ORIGINALS.remove(k);
                if (orig != null) c.write(orig > 0.5);
            }
        } catch (Throwable ignored) {}
        try {
            for (NumRef n : NumRef.candidates(entity)) {
                String k = cls + "#" + n.describe();
                Double orig = PIN_ORIGINALS.remove(k);
                if (orig != null) n.write(orig);
            }
        } catch (Throwable ignored) {}
        FOUND_GATE.remove(cls);
    }

    /** 逐个试探布尔 + 数值门候选：翻转布尔 / 数值钉极大值 → 重写目标 → 2 tick 验证是否钉住。 */
    private static void hunt(LivingEntity entity, double target, String cls, UUID uuid) {
        HUNTING.add(uuid);
        List<BoolRef> bools = BoolRef.candidates(entity);
        List<NumRef> nums = NumRef.candidates(entity);
        if (bools.isEmpty() && nums.isEmpty()) {
            HUNTING.remove(uuid);
            NEGATIVE.add(cls);
            LOGGER.warn("[GateHunt] {} 无候选", cls);
            return;
        }
        // 诊断：dump 候选列表（每类一次），用于确认门控是否在候选内
        if (DIAG.add(cls)) {
            StringBuilder sb = new StringBuilder();
            for (BoolRef c : bools) sb.append(c.describe()).append(", ");
            for (NumRef n : nums) sb.append(n.describe()).append(", ");
            LOGGER.warn("[GateHunt] {} 候选(布尔{} 数值{}): {}", cls, bools.size(), nums.size(), sb);
        }
        probeBool(entity, target, cls, uuid, bools, nums, 0);
    }

    private static void probeBool(LivingEntity entity, double target, String cls, UUID uuid,
                                  List<BoolRef> bools, List<NumRef> nums, int idx) {
        if (entity.isRemoved() || entity.level().isClientSide() || idx >= bools.size()
                || idx >= MAX_CANDIDATES) {
            // 布尔候选耗尽 → 转数值门猎杀
            probeNum(entity, target, cls, uuid, nums, 0);
            return;
        }
        BoolRef cand = bools.get(idx);
        boolean orig = cand.read();
        LOGGER.info("[GateHunt] 试探 {}#{} 当前={} → 翻转", cls, cand.describe(), orig);
        double before = readLogical(entity);   // 探测前基线：验收要求「值确实因这次探测而变」，不能只靠近 target
        cand.write(!orig);
        writeTarget(entity, target);
        HealthModificationScheduler.schedule(entity,
            HealthModificationScheduler.once("gate-probe", 2, e -> {
                if (e == null || e.isRemoved()) {
                    cand.write(orig);
                    return;
                }
                double now = readLogical(e);
                if (gateAccepted(now, target, before)) {
                    rememberPin(cls + "#" + cand.describe(), orig ? 1.0 : 0.0);
                    FOUND_GATE.put(cls, cand.describe() + "=" + !orig);                    HUNTING.remove(uuid);
                    // 有权威布尔门控在管事 ⇒ 非常规生命值实体（粘性）
                    HealthTier.markIrregular(e.getClass(), "命中权威布尔门控 " + cand.describe());
                    LOGGER.warn("[GateHunt] 命中权威门控 {}#{} → 置 {}（值已钉住={}，探测前={}）",
                        cls, cand.describe(), !orig, now, before);
                    reverifyPin(e, target, cls, uuid, cand, orig ? 1.0 : 0.0, !orig ? 1.0 : 0.0);
                } else {
                    // 诊断：2 tick 后候选当前值（判断翻转是否被权威程序拉回）
                    boolean persisted = cand.read();
                    cand.write(orig);
                    writeTarget(e, target);
                    LOGGER.info("[GateHunt] {}#{} 未命中（当前={}，探测前={}，候选2tick后={}）→ 还原，继续",
                        cls, cand.describe(), now, before, persisted);
                    probeBool(e, target, cls, uuid, bools, nums, idx + 1);
                }
            }));
    }

    /** 数值门猎杀：把数值候选钉到极大值（使「==0 / <阈值」判定失效），重写目标 → 2 tick 验证。 */
    private static void probeNum(LivingEntity entity, double target, String cls, UUID uuid,
                                 List<NumRef> nums, int idx) {
        if (entity.isRemoved() || entity.level().isClientSide() || idx >= nums.size()
                || idx >= MAX_CANDIDATES) {
            HUNTING.remove(uuid);
            NEGATIVE.add(cls);
            LOGGER.warn("[GateHunt] {} 未找到权威门控（试探 {} 数值）", cls, Math.min(idx, nums.size()));
            return;
        }
        NumRef cand = nums.get(idx);
        double orig = cand.read();
        LOGGER.info("[GateHunt] 试探数值 {}#{} 当前={} → 钉极大值", cls, cand.describe(), orig);
        double before = readLogical(entity);   // 探测前基线（同上）
        cand.write(1e9);
        writeTarget(entity, target);
        HealthModificationScheduler.schedule(entity,
            HealthModificationScheduler.once("gate-probe-num", 2, e -> {
                if (e == null || e.isRemoved()) {
                    cand.write(orig);
                    return;
                }
                double now = readLogical(e);
                if (gateAccepted(now, target, before)) {
                    rememberPin(cls + "#" + cand.describe(), orig);
                    FOUND_GATE.put(cls, cand.describe() + "=" + 1e9);
                    HUNTING.remove(uuid);
                    HealthTier.markIrregular(e.getClass(), "命中权威数值门控 " + cand.describe());
                    LOGGER.warn("[GateHunt] 命中数值门控 {}#{} → 钉 1e9（值已钉住={}，探测前={}）",
                        cls, cand.describe(), now, before);
                    reverifyPin(e, target, cls, uuid, cand, orig, 1e9);
                } else {
                    cand.write(orig);
                    writeTarget(e, target);
                    LOGGER.info("[GateHunt] 数值 {}#{} 未命中（当前={}，探测前={}）→ 还原，继续",
                        cls, cand.describe(), now, before);
                    probeNum(e, target, cls, uuid, nums, idx + 1);
                }
            }));
    }

    /**
     * 门控验收判据（C：止血误判）。
     *
     * <p>旧判据只有「2 tick 后血量接近 target」，容差还是 {@code max(1, target*5%)}——
     * target=362 时容差 18，于是<b>我们自己上一笔写入</b>就足以让一个无关候选（某个技能冷却）
     * 被判成"权威门控"，然后被永久钉到 1e9（既没还原、又按类缓存，之后连验证都不做了）。
     * 现在要求两条同时成立：</p>
     * <ol>
     *   <li>值确实钉在 target 附近（容差收紧到 {@code max(0.5, target*1%)}）；</li>
     *   <li>值与「探测前基线」不同 —— 即这次探测真的改变了血量，而不是原本就已经在 target 附近。</li>
     * </ol>
     */
    private static boolean gateAccepted(double now, double target, double before) {
        if (!Double.isFinite(now)) return false;
        double tol = Math.max(0.5, Math.abs(target) * 0.01);
        if (Math.abs(now - target) > tol) return false;
        return !Double.isFinite(before) || Math.abs(now - before) > 0.5;
    }

    /**
     * 双向复核（C）：命中后 2 tick 再验一次——真门控保持钉住；若又失守，说明这是巧合命中，
     * 还原候选原值、撤掉 FOUND_GATE、继续下一个候选（并把该候选计入负向）。
     */
    private static void reverifyPin(LivingEntity entity, double target, String cls, UUID uuid,
                                    Object cand, double orig, double pinned) {
        HealthModificationScheduler.schedule(entity,
            HealthModificationScheduler.once("gate-reverify", 2, e -> {
                if (e == null || e.isRemoved()) return;
                double now = readLogical(e);
                if (gateAccepted(now, target, Double.NaN)) {
                    LOGGER.warn("[GateHunt] {} 门控复核通过（当前={} 目标={}）", cls, now, target);
                    return;
                }
                // 失守：还原 + 撤销
                try {
                    if (cand instanceof BoolRef b) b.write(orig > 0.5);
                    else if (cand instanceof NumRef n) n.write(orig);
                } catch (Throwable ignored) {}
                PIN_ORIGINALS.remove(cls + "#" + describe(cand));
                FOUND_GATE.remove(cls);
                HUNTING.remove(uuid);
                writeTarget(e, target);
                LOGGER.warn("[GateHunt] {} 门控复核失败（当前={} 目标={}）→ 已还原候选，判定为巧合命中",
                    cls, now, target);
            }));
    }

    private static String describe(Object cand) {
        if (cand instanceof BoolRef b) return b.describe();
        if (cand instanceof NumRef n) return n.describe();
        return String.valueOf(cand);
    }

    /** 把目标值写到主槽 + vanilla 通道（猎杀期间的重写；vanilla 仅无槽实体写，防显示矛盾）。 */
    private static void writeTarget(LivingEntity entity, double target) {
        try {
            if (EntityHealthLocator.locate(entity) != null) {
                EntityHealthLocator.writeLocated(entity, target);
            } else if (DirectHealthFallback.VANILLA_HEALTH_ACCESSOR != null) {
                DirectHealthFallback.setFloatChannelValue(entity,
                    DirectHealthFallback.VANILLA_HEALTH_ACCESSOR, (float) target, true);
            }
        } catch (Throwable ignored) {}
    }

    /** 读目标当前逻辑血量。 */
    private static double readLogical(LivingEntity entity) {
        try {
            Double v = EntityHealthLocator.readLocated(entity);
            if (v != null && Double.isFinite(v)) return v;
        } catch (Throwable ignored) {}
        try {
            return entity.getHealth();
        } catch (Throwable t) {
            return Double.NaN;
        }
    }

    /** 死亡清理（die 处理器调用，防任务泄漏）。 */
    public static void remove(LivingEntity entity) {
        if (entity == null) return;
        restorePinned(entity);            // 把被钉住的候选字段还原（否则无关字段永久停在 1e9）
        PIN_TRIES.remove(entity.getUUID());
        HUNTING.remove(entity.getUUID());
        HealthModificationScheduler.remove(entity, "gate-verify");
        HealthModificationScheduler.remove(entity, "gate-probe");
        HealthModificationScheduler.remove(entity, "gate-probe-num");
        HealthModificationScheduler.remove(entity, "gate-reverify");
    }

    private static final org.slf4j.Logger LOGGER = net.minecraft.client.yiz.tizMod.LOGGER;
}
