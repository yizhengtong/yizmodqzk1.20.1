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

    private GateHunt() {}

    /** 全量直改后调用：2 tick 后验证写回，被拉回则启动门控猎杀。 */
    public static void verifyAndHunt(LivingEntity entity, double target) {
        if (entity == null || entity.level().isClientSide() || entity.isRemoved()) return;
        // 每一刀都验证（不只在 target=0）：有「每 tick 权威拉回」的实体，中段伤害也会被拉回，
        // 需要提前猎杀门控，否则规则 A 的直接减法永远被扣血上限挡住。
        // 不用 isDeadOrDying() 提前返回——「血打 0 瞬间 isDeadOrDying 翻 true、下一 tick 又复活」的
        // 实体会因此漏猎。是否真死交给 2 tick 验证里的 isRemoved 判断。
        UUID uuid = entity.getUUID();
        if (HUNTING.contains(uuid)) return;
        String cls = entity.getClass().getName();
        if (FOUND_GATE.containsKey(cls) || NEGATIVE.contains(cls)) return;   // 已缓存
        HealthModificationScheduler.remove(entity, "gate-verify");
        HealthModificationScheduler.schedule(entity,
            HealthModificationScheduler.once("gate-verify", 2, e -> {
                if (e == null || e.isRemoved()) return;
                double now = readLogical(e);
                if (!Double.isFinite(now)) return;
                double tol = Math.max(STICK_TOLERANCE, target * 0.05);
                // 只在「血被向上拉回」时猎杀（now > target + tol）；向下偏离可能是并发伤害，不猎。
                if (now > target + tol) {
                    LOGGER.warn("[GateHunt] {} 写回被拉回 当前={} 目标={} → 启动门控猎杀",
                        cls, now, target);
                    hunt(e, target, cls, uuid);
                } else {
                    LOGGER.info("[GateHunt] {} 写回保持 当前={} 目标={}（无权威对抗）", cls, now, target);
                }
            }));
    }

    /** 逐个试探布尔候选：翻转 + 重写目标 → 2 tick 验证是否钉住。 */
    private static void hunt(LivingEntity entity, double target, String cls, UUID uuid) {
        HUNTING.add(uuid);
        List<BoolRef> candidates = BoolRef.candidates(entity);
        if (candidates.isEmpty()) {
            HUNTING.remove(uuid);
            NEGATIVE.add(cls);
            LOGGER.warn("[GateHunt] {} 无布尔候选", cls);
            return;
        }
        probeNext(entity, target, cls, uuid, candidates, 0);
    }

    private static void probeNext(LivingEntity entity, double target, String cls,
                                  UUID uuid, List<BoolRef> candidates, int idx) {
        if (entity.isRemoved() || entity.level().isClientSide() || idx >= candidates.size()
                || idx >= MAX_CANDIDATES) {
            HUNTING.remove(uuid);
            NEGATIVE.add(cls);
            LOGGER.warn("[GateHunt] {} 未找到权威门控（试探 {} 个候选）", cls, idx);
            return;
        }
        BoolRef cand = candidates.get(idx);
        boolean orig = cand.read();
        LOGGER.info("[GateHunt] 试探 {}#{} 当前={} → 翻转", cls, cand.describe(), orig);
        cand.write(!orig);
        writeTarget(entity, target);
        HealthModificationScheduler.schedule(entity,
            HealthModificationScheduler.once("gate-probe", 2, e -> {
                if (e == null || e.isRemoved()) {
                    cand.write(orig);
                    return;
                }
                double now = readLogical(e);
                if (Double.isFinite(now) && Math.abs(now - target) <= Math.max(STICK_TOLERANCE, target * 0.05)) {
                    // 命中：该布尔是权威门控 → 保持翻转（击穿完成）
                    FOUND_GATE.put(cls, cand.describe() + "=" + !orig);
                    HUNTING.remove(uuid);
                    LOGGER.warn("[GateHunt] 命中权威门控 {}#{} → 置 {}（值已钉住={}）",
                        cls, cand.describe(), !orig, now);
                } else {
                    cand.write(orig);   // 还原未命中项
                    writeTarget(e, target);
                    LOGGER.info("[GateHunt] {}#{} 未命中（当前={}）→ 还原，继续", cls, cand.describe(), now);
                    probeNext(e, target, cls, uuid, candidates, idx + 1);
                }
            }));
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
        HUNTING.remove(entity.getUUID());
        HealthModificationScheduler.remove(entity, "gate-verify");
        HealthModificationScheduler.remove(entity, "gate-probe");
    }

    private static final org.slf4j.Logger LOGGER = net.minecraft.client.yiz.tizMod.LOGGER;
}
