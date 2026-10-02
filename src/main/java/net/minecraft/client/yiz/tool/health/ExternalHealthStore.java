package net.minecraft.client.yiz.tool.health;

import net.minecraft.client.yiz.tool.key.FieldHandle;
import net.minecraft.world.entity.LivingEntity;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 通用「外部静态单例藏血 Map」发现 + 改写（S9 模式）。
 *
 * <p>部分模组把真实血量搬出实体本体：<b>静态 Map</b>（K = UUID / 实体 id / 实体 / 弱引用）
 * → 条目对象（V = 数值，或「数值 + 一个加密载体」的容器），并配一个每 tick 看门狗回读拉回。</p>
 *
 * <p><b>本类不认识任何密码算法</b>，也不含任何类名/字段名/包名。它只做三件事：</p>
 * <ol>
 *   <li><b>发现</b>：静态 Map 字段（由 {@link HealthDiscovery} 按「K 是实体身份型」的泛型签名给出）
 *       → 按 UUID / id / 实体 / 弱引用键匹配条目；键匹配不到时，再按「值的字段里持有该实体身份」
 *       做一次<b>有界</b>兜底扫描（小表才扫，防大 Map 拖慢攻击路径）。</li>
 *   <li><b>判定</b>：条目里的加密载体交给 {@link ExternalCodecPool} —— 加载期已按类型签名收好
 *       「造密文 / 读明文」的候选入口，现场逐个提试，直到「读出来的值与实体自身血量读数一致」，
 *       由此定出该存储的<b>语义</b>（直映 = 值就是血量；逆序 = 值 = 上限 − 血量）。全程只读。</li>
 *   <li><b>改写</b>：写入时不碰密码本身，而是反复调用目标自己的构造入口造载体、用自己的读出入口
 *       读回来、按误差修正输入，直到读回值等于目标明文，命中后才挂进字段；随后用实体血量读数复核。
 *       同时同步条目里的明文镜像槽（与当前血同值的字段），并对静态写入口做兜底提试。</li>
 * </ol>
 *
 * <p>方案按「条目类」缓存（含失败计数与自愈重扫）；所有判定失败都只是返回 false，
 * 由主管道继续走其它通用通道（累积软压 / 死亡链）。</p>
 */
public final class ExternalHealthStore {

    private static final org.slf4j.Logger LOGGER = net.minecraft.client.yiz.tizMod.LOGGER;
    private static final Set<String> LOGGED = ConcurrentHashMap.newKeySet();
    private static final Set<String> WRITE_DIAG = ConcurrentHashMap.newKeySet();
    /** 实体写入口兜底的诊断（每持有类一次）。 */
    private static final Set<String> WRITER_DIAG = ConcurrentHashMap.newKeySet();

    /** 条目类 → 读写方案（含负缓存与失败计数）。 */
    private static final Map<Class<?>, Plan> PLANS = new ConcurrentHashMap<>();
    private static final Object PLAN_LOCK = new Object();

    private ExternalHealthStore() {}

    // ==================== 存储语义 ====================

    /** 载体/字段的存储语义：值就是血量，或值 = 上限 − 血量（累计伤害）。 */
    enum Sense {
        DIRECT, INVERSE;

        double toStored(double cap, double health) { return this == INVERSE ? cap - health : health; }
        double toHealth(double cap, double stored) { return this == INVERSE ? cap - stored : stored; }
        Sense flip() { return this == INVERSE ? DIRECT : INVERSE; }
    }

    // ==================== 公共 API ====================

    /** 从外部藏血 Map 读真实血量；非此类实体 / 判定不出返回 null。 */
    public static Double readHealth(LivingEntity entity) {
        if (entity == null || entity.level().isClientSide()) return null;
        Double oracle = oracle(entity);
        if (oracle == null) return null;
        double attrCap = capOf(entity, null);
        for (Candidate c : candidateMaps(entity)) {
            Match m = matchEntry(c.map, entity);
            if (m == null) continue;
            double cap = Double.isFinite(attrCap) ? attrCap : capOf(entity, c.owner);
            if (!Double.isFinite(cap) || cap <= 0) continue;
            // 值本身就是数值：只认「与实体读数一致」的那一种语义（直映 / 上限−血量）
            if (m.value instanceof Number n) {
                double raw = n.doubleValue();
                if (!Double.isFinite(raw)) continue;
                if (near(raw, oracle)) {
                    logOnce(entity, m.value, "Map 数值条目(直映)");
                    return Math.max(0.0, raw);
                }
                if (near(cap - raw, oracle)) {
                    logOnce(entity, m.value, "Map 数值条目(逆序)");
                    return Math.max(0.0, cap - raw);
                }
                continue;
            }
            Double v = readFromEntry(m.value, c.owner, entity, cap, oracle);
            if (v != null) return v;
        }
        return null;
    }

    /** 写真实血量到外部藏血 Map；返回是否写入并命中（行为验证通过）。 */
    public static boolean writeHealth(LivingEntity entity, double target) {
        if (entity == null || entity.level().isClientSide()) return false;
        Double oracle = oracle(entity);
        if (oracle == null) return false;
        double attrCap = capOf(entity, null);
        boolean any = false;
        for (Candidate c : candidateMaps(entity)) {
            Match m = matchEntry(c.map, entity);
            if (m == null) continue;
            double cap = Double.isFinite(attrCap) ? attrCap : capOf(entity, c.owner);
            if (!Double.isFinite(cap) || cap <= 0) continue;
            double want = Math.max(0.0, Math.min(cap, target));
            // 值本身就是数值：按原装箱类型写回 Map（Float 之类不可变，改对象字段没有意义）
            if (m.value instanceof Number n) {
                if (!net.minecraft.client.yiz.core.YizConfig.bool("compat.allow_number_entry")) continue;
                Double stored = plainForWrite(n.doubleValue(), cap, oracle, want);
                if (stored != null && writeNumberEntry(c.map, m.key, n, stored)) any = true;
                continue;
            }
            if (writeIntoEntry(m.value, c.owner, entity, cap, oracle, want)) any = true;
        }
        return any;
    }

    /**
     * 数值条目的写入值：语义先与实体读数对齐（直映 → 目标血；上限−血量 → 上限−目标血），
     * 对不齐就返回 null（宁可不写，也不猜）。
     */
    private static Double plainForWrite(double current, double cap, double oracle, double target) {
        if (near(current, oracle)) return target;
        if (near(cap - current, oracle)) return cap - target;
        return null;
    }

    /**
     * 把新值按<b>原装箱类型</b>写回 Map（Long/Integer/Double/Float/Short/Byte 各自保持）。
     *
     * <p>不保持类型会让持有方下次按自己的类型取值时 ClassCastException —— 崩的是对方的 tick，
     * 栈里看不到本模组任何一帧。写完回读验证：读回来对不上就不算成功。</p>
     */
    private static boolean writeNumberEntry(Map<?, ?> map, Object key, Number existing, double value) {
        try {
            Object boxed = boxLike(existing, value);
            @SuppressWarnings("unchecked")
            Map<Object, Object> m = (Map<Object, Object>) map;
            m.put(key, boxed);
            Object back = m.get(key);
            return back instanceof Number b && b.doubleValue() == ((Number) boxed).doubleValue();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 按原有装箱类型装箱（浮点保持 float，整型四舍五入）。 */
    private static Object boxLike(Number existing, double value) {
        if (existing instanceof Long) return Math.round(value);
        if (existing instanceof Integer) return (int) Math.round(value);
        if (existing instanceof Double) return value;
        if (existing instanceof Short) return (short) Math.round(value);
        if (existing instanceof Byte) return (byte) Math.round(value);
        return (float) value;
    }

    // ==================== 读 ====================

    private static Double readFromEntry(Object entry, Class<?> owner, LivingEntity entity,
                                        double cap, double oracle) {
        Plan plan = plan(entry, owner, cap, oracle);
        // 1) 加密载体：解出来的值必须与实体自身读数一致（按字段自己的模型换算；模型未定的字段顺便收样本）
        if (plan.handle != null && plan.decoderIdx >= 0) {
            for (CarrierField c : plan.carriers) {
                Object cur = get(entry, c.field);
                if (cur == null) continue;
                Double raw = plan.handle.decodeWith(plan.decoderIdx, cur);
                if (raw == null) continue;
                if (!c.usable()) {
                    c.observe(raw, oracle);                    // 攒「明文 ↔ 血量」样本，供仿射模型拟合
                    continue;
                }
                double health = c.toHealth(cap, raw);
                if (Double.isFinite(health) && near(health, oracle)) {
                    logOnce(entity, entry, "载体解码");
                    return Math.max(0.0, health);
                }
            }
        }
        // 2) 明文镜像槽
        for (Slot s : plan.slots) {
            Double v = number(entry, s.field);
            if (v == null) continue;
            double health = s.inverse ? cap - v : v;
            if (near(health, oracle)) {
                logOnce(entity, entry, "明文槽");
                return Math.max(0.0, health);
            }
        }
        return null;
    }

    // ==================== 写 ====================

    private static boolean writeIntoEntry(Object entry, Class<?> owner, LivingEntity entity,
                                          double cap, double oracle, double target) {
        Plan plan = plan(entry, owner, cap, oracle);
        boolean any = false;
        // 1) 明文镜像槽同步（与当前血同值的字段：目标值直写 / 逆序写）
        for (Slot s : plan.slots) {
            double stored = s.inverse ? cap - target : target;
            if (setNumber(entry, s.field, stored)) any = true;
        }
        // 2) 加密载体：委托目标自己的入口（生成 → 读出 → 修正）；每个字段按自己的语义写
        if (plan.handle != null && !plan.carriers.isEmpty()) {
            if (applyCarrier(plan, entry, entity, cap, target)) any = true;
        }
        // 3) 兜底：模块自己的静态写入口（小步探针验证语义后才用）
        if (!any) any = viaEntityWriter(plan, entity, cap, oracle, target);
        // 4) 写后复核 + 失败自愈：连续多次写不进 → 作废方案，下次重新判定
        Double now = oracle(entity);
        boolean hit = now != null && near(now, target);
        if (!hit) {
            Double viaEntry = readFromEntry(entry, owner, entity, cap, now == null ? oracle : now);
            hit = viaEntry != null && near(viaEntry, target);
        }
        if (hit) {
            plan.fails = 0;
        } else if (++plan.fails >= 3) {
            PLANS.remove(entry.getClass(), plan);
            LOGGER.warn("[ExtStore] {} 连续 {} 次写后复核不通过 → 作废方案，下次重新判定",
                    entry.getClass().getSimpleName(), plan.fails);
        }
        return any;
    }

    /**
     * 按各字段自己的语义写载体：累计量字段写「上限−目标血」，血量镜像字段写「目标血」，
     * 上限字段根本不在名单里（见 {@link #classifyCarrier}）。逐字段独立复核，
     * 最终以「实体血量读数是否跟到目标」为准；失败时把每个字段的实际结果写进日志（诊断用）。
     */
    private static boolean applyCarrier(Plan plan, Object entry, LivingEntity entity, double cap, double target) {
        Object template = firstCarrier(entry, plan);       // 同型参照：某个副本为 null 时用它当标定基准
        Double before = oracle(entity);
        int written = 0, verified = 0, skipped = 0;
        StringBuilder detail = new StringBuilder();
        for (CarrierField c : plan.carriers) {
            if (!c.usable()) {
                skipped++;                                 // 模型未定（等学习样本）→ 本刀不碰
                continue;
            }
            double want = c.toPlain(cap, target);
            Object cur = get(entry, c.field);
            ExternalCodecPool.WriteResult r = plan.handle.writeWith(plan.decoderIdx, want, cur, template);
            if (!r.ok) {
                detail.append(c.field.getName()).append("=未写入 ");
                continue;
            }
            if (!r.inPlace && !setObject(entry, c.field, r.carrier)) {
                detail.append(c.field.getName()).append("=挂载失败 ");
                continue;
            }
            written++;
            Object now = get(entry, c.field);
            Double raw = now == null ? null : plan.handle.decodeWith(plan.decoderIdx, now);
            boolean ok = raw != null && near(raw, want);
            if (ok) verified++;
            detail.append(c.field.getName()).append(ok ? "=OK" : ("=解回" + raw)).append(' ');
        }
        Double after = oracle(entity);
        boolean follows = after != null && near(after, target);
        // 写成功后把这刀当作一条「明文 ↔ 血量」观测（模型未定的字段靠它攒样本）
        if (written > 0 && verified > 0) {
            for (CarrierField c : plan.carriers) {
                if (c.inverse != null) continue;
                Object now = get(entry, c.field);
                if (now == null) continue;
                Double raw = plan.handle.decodeWith(plan.decoderIdx, now);
                if (raw != null) c.observe(raw, follows ? target : (after == null ? raw : after));
            }
        }
        if (WRITE_DIAG.add(entry.getClass().getName())) {
            LOGGER.warn("[ExtStore] {} 载体写 目标={} 字段 {}/{} 复核 {} 待学={} 血量 {}→{} | {}",
                    entry.getClass().getSimpleName(), target, verified, plan.carriers.size(),
                    follows ? "通过" : "未跟随", skipped, before, after, detail.toString().trim());
        }
        if (!follows) return false;                        // 血量没跟到目标 → 交给上层/其它通道
        return written > 0 && verified > 0;
    }

    /** 取一个非空同型载体当参照（字段还没被写过时，用它给编码器做标定）。 */
    private static Object firstCarrier(Object entry, Plan plan) {
        for (CarrierField c : plan.carriers) {
            Object v = get(entry, c.field);
            if (v != null) return v;
        }
        return null;
    }

    /** 兜底：调用「静态 (实体, 数值)」写入口。先用无损 + 小步探针判定语义，再写目标值。 */
    private static boolean viaEntityWriter(Plan plan, LivingEntity entity, double cap, double oracle, double target) {
        if (!net.minecraft.client.yiz.core.YizConfig.bool("compat.allow_entity_writer")) return false;
        if (plan.owner == null) return false;
        if (plan.writer == null) {
            for (Method m : plan.writers) {
                Sense s = probeWriter(m, entity, cap, oracle);
                if (s == null) continue;
                plan.writer = m;
                plan.writerSense = s;
                break;
            }
        }
        if (plan.writer == null) {
            if (WRITER_DIAG.add(planKey(plan))) {
                LOGGER.warn("[ExtStore] 写入口探针全部未命中：候选 {} 个（持有类 {}）",
                        plan.writers.size(), plan.owner.getSimpleName());
            }
            return false;
        }
        double stored = plan.writerSense.toStored(cap, target);
        boolean ok = ExternalCodecPool.callEntityWriter(plan.writer, entity, stored);
        if (WRITER_DIAG.add(planKey(plan))) {
            LOGGER.warn("[ExtStore] 走实体写入口 {} 语义={} 写入值={} 调用{}",
                    plan.writer.getName(), plan.writerSense, stored, ok ? "成功" : "失败");
        }
        return ok;
    }

    private static String planKey(Plan plan) {
        return plan.owner == null ? "?" : plan.owner.getName();
    }

    /**
     * 写入口语义探针（只在其它通道全失败时使用）：
     * 无损探针（写入「当前血」对应的值 → 血量不变）先定语义，再用小步探针（少 1 点血）
     * 确认它确实驱动血量，最后还原。两步都在同一 tick 内完成，看门狗不会介入。
     */
    private static Sense probeWriter(Method m, LivingEntity entity, double cap, double oracle) {
        for (Sense s : Sense.values()) {
            double noop = s.toStored(cap, oracle);
            if (!ExternalCodecPool.callEntityWriter(m, entity, noop)) continue;
            Double after = oracle(entity);
            if (after == null || !near(after, oracle)) continue;      // 该语义不成立
            double step = oracle > 1.0 ? oracle - 1.0 : oracle + 1.0;
            boolean moved = true;
            if (Math.abs(step - oracle) > 1e-6) {
                ExternalCodecPool.callEntityWriter(m, entity, s.toStored(cap, step));
                Double a2 = oracle(entity);
                moved = a2 != null && near(a2, step);
            }
            ExternalCodecPool.callEntityWriter(m, entity, noop);      // 还原
            if (moved) return s;
        }
        return null;
    }

    // ==================== 方案判定 ====================

    private static final class Slot {
        final Field field;
        final boolean inverse;   // true: 存的是「上限 − 血量」

        Slot(Field field, boolean inverse) {
            this.field = field;
            this.inverse = inverse;
        }
    }

    /**
     * 加密载体字段 + 它自己的存储语义。
     *
     * <p><b>为什么必须逐字段判</b>：同一个条目里可能并存「累计量」「血量镜像」「上限」三种同型密文字段
     * （后缀换个名字而已，类型完全一样）。把同型字段一律写同一个明文，会把<b>上限</b>字段一起改掉：
     * 打非致死伤害时血量会被算成 {@code 上限−累计量 = 0} → 等于"非致死伤害直接致死"。</p>
     *
     * <p><b>为什么不写死「明文 = 血量 或 上限−血量」</b>：那只覆盖最朴素的两种存储。字段语义分两级判：</p>
     * <ol>
     *   <li><b>规范模型</b>：用「解出值 vs 当前血 / 上限」当场判出累计量或镜像；</li>
     *   <li><b>样本表</b>：判不出就记录 {@code (明文, 血量)} 观测，攒够样本后建立<b>分段线性</b>映射
     *       （覆盖百分制/千分制/取负/带偏移/分辨率受限的取整存储；纯仿射只是它的特例）。
     *       是否启用由<b>留一验证</b>裁决：拿掉任一样本、用其余样本插值预测它，误差必须落在容差内；
     *       再加单调性校验，把「与血量无关的常量字段」（典型就是上限）和噪声表刷掉。</li>
     * </ol>
     *
     * <p>已知边界：分段线性不覆盖真正的非线性（对数/查表跳变）；分辨率受限（取整）存储只能逼近，
     * 且写后复核仍按严格容差判定 —— 逼近不到就按失败处理，交给其它通道，不做假成功。</p>
     */
    private static final class CarrierField {
        /** 样本上限（同一血量只留最新一条）。 */
        private static final int MAX_OBS = 12;

        final Field field;
        /** 规范模型：true = 累计量（明文 = 上限−血量）；false = 血量镜像（明文 = 血量）；null = 规范模型不成立。 */
        volatile Boolean inverse;
        /** 观测样本 {明文, 血量}，按血量升序。 */
        final List<double[]> obs = new ArrayList<>();
        /** 样本表是否通过留一验证（通过才允许用于写入）。 */
        private volatile boolean tableOk;

        CarrierField(Field field, Boolean inverse) {
            this.field = field;
            this.inverse = inverse;
        }

        boolean learned() { return tableOk && obs.size() >= 2; }

        /** 可用 = 规范模型成立，或样本表通过验证。 */
        boolean usable() { return inverse != null || learned(); }

        double toPlain(double cap, double health) {
            if (inverse != null) return Boolean.TRUE.equals(inverse) ? cap - health : health;
            return interpolatePlain(health);
        }

        double toHealth(double cap, double plain) {
            if (inverse != null) return Boolean.TRUE.equals(inverse) ? cap - plain : plain;
            return interpolateHealth(plain);
        }

        /**
         * 记录一次「该字段明文 ↔ 实体血量」观测。规范模型已成立时不再学习（不打扰已知正确的路径）；
         * 模型未定时攒样本并重新验证。
         */
        synchronized void observe(double plain, double health) {
            if (inverse != null || !Double.isFinite(plain) || !Double.isFinite(health)) return;
            for (int i = 0; i < obs.size(); i++) {
                double[] o = obs.get(i);
                if (near(o[1], health)) {
                    o[0] = plain;                       // 同一血量：只留最新明文
                    validate();
                    return;
                }
            }
            int at = obs.size();
            for (int i = 0; i < obs.size(); i++) {
                if (health < obs.get(i)[1]) {
                    at = i;
                    break;
                }
            }
            obs.add(at, new double[]{plain, health});
            while (obs.size() > MAX_OBS) obs.remove(obs.size() - 1);
            validate();
        }

        /**
         * 留一验证 + 单调性校验：
         * ① 样本 ≥4 且血量至少 3 个不同取值（两点定线没有余量）；
         * ② 逐点留一：拿掉该点后用<b>与写入同一条插值/外推公式</b>预测它，误差 ≤ 该处的分辨率容差；
         * ③ 相邻段斜率同号且不全为 0（常量字段=上限类，直接判废）。
         *
         * <p>注意：验证只证明"模型自洽"，不证明"字段就是血量"。最终裁决永远是写后用实体读数复核 ——
         * 噪声字段即使骗过验证，也骗不过写后复核（会被判失败并退回其它通道）。</p>
         */
        private void validate() {
            tableOk = false;
            if (obs.size() < 4) return;
            double first = obs.get(0)[1];
            int distinct = 0;
            for (double[] o : obs) if (Math.abs(o[1] - first) > 1e-6) distinct++;
            if (distinct < 3) return;
            double sign = 0;
            for (int i = 1; i < obs.size(); i++) {
                double dh = obs.get(i)[1] - obs.get(i - 1)[1];
                double dp = obs.get(i)[0] - obs.get(i - 1)[0];
                if (Math.abs(dh) <= 1e-9) continue;
                double slope = dp / dh;
                if (Math.abs(slope) <= 1e-9) continue;
                double s = Math.signum(slope);
                if (sign == 0) sign = s;
                else if (s != sign) return;                          // 非单调 → 判废
            }
            if (sign == 0) return;                                   // 常量字段（上限类）→ 判废
            int tested = 0;
            for (int i = 0; i < obs.size(); i++) {
                double[] hold = obs.get(i);
                Double pred = interpolate(hold[1], i);               // 与写入同一条公式
                if (pred == null) continue;
                tested++;
                double gap = localGap(i, hold[1]);
                double lim = Math.max(Math.max(0.5, Math.abs(hold[0]) * 0.001), gap * 0.5);
                if (Math.abs(pred - hold[0]) > lim) return;          // 留一预测不准 → 判废
            }
            if (tested == 0) return;
            tableOk = true;
        }

        /**
         * 预测血量 {@code health} 处的明文（{@code skip} = 留一验证时要拿掉的样本下标，-1 表示不拿掉）。
         * 区间内线性插值；区间外用最近一段的斜率外推 —— 判验证与写入走的是同一段代码。
         */
        private Double interpolate(double health, int skip) {
            List<double[]> rest = new ArrayList<>(obs.size());
            for (int i = 0; i < obs.size(); i++) if (i != skip) rest.add(obs.get(i));
            if (rest.size() < 2) return null;
            double[] lo = rest.get(0);
            double[] hi = rest.get(rest.size() - 1);
            if (health <= lo[1]) return extend(rest.get(0), rest.get(1), health);
            if (health >= hi[1]) return extend(rest.get(rest.size() - 2), hi, health);
            for (int i = 1; i < rest.size(); i++) {
                double[] a = rest.get(i - 1);
                double[] b = rest.get(i);
                if (health <= b[1]) {
                    if (Math.abs(b[1] - a[1]) <= 1e-9) return b[0];
                    double t = (health - a[1]) / (b[1] - a[1]);
                    return a[0] + t * (b[0] - a[0]);
                }
            }
            return hi[0];
        }

        /** 预测点附近剩余样本的间距，当作该处的"分辨率"（取整存储的量化误差上限约等于它的一半）。 */
        private double localGap(int skip, double health) {
            double loH = Double.NaN;
            double hiH = Double.NaN;
            for (int i = 0; i < obs.size(); i++) {
                if (i == skip) continue;
                double h = obs.get(i)[1];
                if (h <= health) loH = h;
                if (h >= health && Double.isNaN(hiH)) hiH = h;
            }
            if (!Double.isNaN(loH) && !Double.isNaN(hiH)) return Math.abs(hiH - loH);
            double min = Double.NaN;
            double prev = Double.NaN;
            for (int i = 0; i < obs.size(); i++) {
                if (i == skip) continue;
                double h = obs.get(i)[1];
                if (!Double.isNaN(prev) && (Double.isNaN(min) || Math.abs(h - prev) < min)) min = Math.abs(h - prev);
                prev = h;
            }
            return Double.isNaN(min) ? 0 : min;
        }

        /** 按血量插值出明文（超出样本区间则用最近一段的斜率外推）。 */
        private double interpolatePlain(double health) {
            Double v = interpolate(health, -1);
            return v == null ? obs.get(obs.size() - 1)[0] : v;
        }

        private static double extend(double[] a, double[] b, double health) {
            if (Math.abs(b[1] - a[1]) <= 1e-9) return b[0];
            double slope = (b[0] - a[0]) / (b[1] - a[1]);
            return b[0] + slope * (health - b[1]);
        }

        /** 按明文反查血量（读路径用；同样支持外推）。 */
        private double interpolateHealth(double plain) {
            for (int i = 1; i < obs.size(); i++) {
                double[] a = obs.get(i - 1);
                double[] b = obs.get(i);
                double loP = Math.min(a[0], b[0]);
                double hiP = Math.max(a[0], b[0]);
                if (plain >= loP - 1e-9 && plain <= hiP + 1e-9) {
                    if (Math.abs(b[0] - a[0]) <= 1e-9) return b[1];
                    double t = (plain - a[0]) / (b[0] - a[0]);
                    return a[1] + t * (b[1] - a[1]);
                }
            }
            // 区间外：用最近一段外推
            double[] a = obs.get(0);
            double[] b = obs.get(1);
            double best = Math.abs((a[0] + b[0]) / 2 - plain);
            for (int i = 1; i < obs.size(); i++) {
                double[] x = obs.get(i - 1);
                double[] y = obs.get(i);
                double d = Math.abs((x[0] + y[0]) / 2 - plain);
                if (d < best) {
                    best = d;
                    a = x;
                    b = y;
                }
            }
            if (Math.abs(b[0] - a[0]) <= 1e-9) return b[1];
            double slope = (b[1] - a[1]) / (b[0] - a[0]);
            return b[1] + slope * (plain - b[0]);
        }

        String describe() {
            if (inverse != null) return field.getName() + (inverse ? "(累计)" : "(镜像)");
            if (learned()) return field.getName() + "(样本表 " + obs.size() + ")";
            return field.getName() + "(待学 " + obs.size() + ")";
        }
    }

    private static final class Plan {
        final Class<?> owner;                     // 静态 Map 字段的声明类（写入口兜底用）
        final List<Slot> slots = new ArrayList<>();
        final List<CarrierField> carriers = new ArrayList<>();
        final List<Method> writers = new ArrayList<>();
        int pendingCarriers;                      // 规范模型判不出、正在等学习样本的同型字段数（诊断）
        ExternalCodecPool.Handle handle;          // 载体编解码句柄（可为 null）
        volatile int decoderIdx = -1;
        volatile Method writer;
        volatile Sense writerSense = Sense.INVERSE;
        volatile int fails;

        Plan(Class<?> owner) {
            this.owner = owner;
        }
    }

    private static Plan plan(Object entry, Class<?> owner, double cap, double oracle) {
        Class<?> key = entry.getClass();
        Plan p = PLANS.get(key);
        if (p != null && (p.owner == null || owner == null || p.owner == owner)) return p;
        synchronized (PLAN_LOCK) {
            p = PLANS.get(key);
            if (p != null && (p.owner == null || owner == null || p.owner == owner)) return p;
            p = buildPlan(entry, owner, cap, oracle);
            PLANS.put(key, p);
            StringBuilder cf = new StringBuilder();
            for (CarrierField c : p.carriers) cf.append(c.describe()).append(' ');
            StringBuilder sl = new StringBuilder();
            for (Slot s : p.slots) sl.append(s.field.getName()).append(s.inverse ? "(逆序)" : "(直映)").append(' ');
            LOGGER.info("[ExtStore] {} 方案: 载体={}[{}] 解码器={} 待学字段={} 明文槽={}[{}] 写口候选={}",
                    key.getSimpleName(), p.carriers.size(), cf.toString().trim(), p.decoderIdx,
                    p.pendingCarriers, p.slots.size(), sl.toString().trim(), p.writers.size());
            return p;
        }
    }

    private static Plan buildPlan(Object entry, Class<?> owner, double cap, double oracle) {
        Plan p = new Plan(owner);
        List<Field> fields = instanceFields(entry.getClass());

        // —— 载体字段：引用型字段，其（运行期）类型能拿到「造密文 / 读明文」句柄；语义逐字段判 ——
        List<Field> nullCarriers = new ArrayList<>();
        Boolean siblingSense = null;
        for (Field f : fields) {
            if (skipForWrite(f)) continue;
            Class<?> declared = f.getType();
            if (isValueType(declared)) continue;                 // 原始类型/字符串/枚举/JDK/引擎类
            Object cur = get(entry, f);
            Class<?> runtime = cur != null ? cur.getClass() : declared;
            ExternalCodecPool.Handle h = ExternalCodecPool.handleFor(runtime);
            if (h == null) continue;                             // 不是编解码载体
            if (p.handle == null) {
                int idx = resolveDecoder(h, cur, cap, oracle);
                if (idx < 0) continue;
                p.handle = h;
                p.decoderIdx = idx;
            } else if (p.handle.carrierType() != h.carrierType()) {
                continue;                                        // 只处理同一种载体类型，避免混写
            }
            if (cur == null) {
                nullCarriers.add(f);                             // 空副本稍后继承同型字段的语义
                continue;
            }
            Boolean inverse = classifyCarrier(p.handle, p.decoderIdx, cur, cap, oracle);
            CarrierField c = new CarrierField(f, inverse);
            if (inverse == null) {
                // 规范模型不成立：先记一条观测样本，攒够了自动拟合「明文 = a·血量 + b」。
                // 只在这一级判废的字段才走学习；规范模型成立的字段不受影响。
                Double v = p.handle.decodeWith(p.decoderIdx, cur);
                if (v != null) c.observe(v, oracle);
                p.pendingCarriers++;
            } else if (siblingSense == null) {
                siblingSense = inverse;
            }
            p.carriers.add(c);
        }
        // 空副本（还没被写过）：继承同型已判定字段的语义；没有任何参照就先留着等学习
        for (Field f : nullCarriers) {
            p.carriers.add(new CarrierField(f, siblingSense));
            if (siblingSense == null) p.pendingCarriers++;
        }

        // —— 明文槽：条目本体 + 一层下钻里「与当前血同值」或「与上限−当前血同值」的数值字段 ——
        collectSlots(entry, entry, p.slots, cap, oracle, 0);

        // —— 静态写入口候选（持有该 Map 的类上的「(实体, 数值)」方法）——
        if (owner != null) p.writers.addAll(ExternalCodecPool.entityWritersOf(owner));
        return p;
    }

    /**
     * 判单个密文字段的语义：累计量（明文 = 上限−血量）/ 血量镜像（明文 = 血量）/ 判不出（返回 null，转学习）。
     *
     * <p>「≈ 上限」的字段直接返回 null 不参与判语义 —— 那正是把上限写坏的入口；血量满值时镜像与上限会重合，
     * 这种歧义同样返回 null，交给学习路径用「明文是否随血量变化」来否掉（常量字段拟合出 a≈0 会被判废）。</p>
     */
    private static Boolean classifyCarrier(ExternalCodecPool.Handle h, int idx, Object carrier,
                                           double cap, double oracle) {
        Double v = h.decodeWith(idx, carrier);
        if (v == null) return null;
        if (near(v, cap)) return null;                       // 上限字段：不写
        if (near(cap - v, oracle)) return Boolean.TRUE;      // 累计量
        if (near(v, oracle)) return Boolean.FALSE;           // 血量镜像
        return null;
    }

    /** 试出可用解码器：有现场载体时按语义核对；载体为空时按「造对象 → 读回自洽」试。 */
    private static int resolveDecoder(ExternalCodecPool.Handle h, Object sample, double cap, double oracle) {
        if (sample != null) {
            for (int i = 0; i < h.decoderCount(); i++) {
                if (detectSense(h, i, sample, cap, oracle) != null) return i;
            }
        }
        for (int i = 0; i < h.decoderCount(); i++) {
            if (h.writeWith(i, 1.0, null, null).ok) return i;    // 能造出「读回来 == 1.0」的对象
        }
        return -1;
    }

    /** 读出值 vs 实体读数 → 语义；都不吻合返回 null。 */
    private static Sense detectSense(ExternalCodecPool.Handle h, int idx, Object carrier, double cap, double oracle) {
        Double v = h.decodeWith(idx, carrier);
        if (v == null) return null;
        if (near(v, oracle)) return Sense.DIRECT;
        if (near(cap - v, oracle)) return Sense.INVERSE;
        return null;
    }

    private static void collectSlots(Object root, Object holder, List<Slot> out,
                                     double cap, double oracle, int depth) {
        for (Field f : instanceFields(holder.getClass())) {
            if (skipForWrite(f)) continue;
            Class<?> t = f.getType();
            if (isNumeric(t)) {
                Double v = number(holder, f);
                if (v == null) continue;
                if (near(v, cap) && !near(cap - v, oracle)) continue;     // 上限字段：绝不写
                if (near(v, oracle)) out.add(new Slot(f, false));
                else if (near(cap - v, oracle)) out.add(new Slot(f, true));
            } else if (depth == 0 && !isValueType(t)) {
                Object inner = get(holder, f);
                // 下钻一层找明文镜像；载体对象（有编解码句柄的加密容器）内部不碰
                if (inner != null && inner != root && ExternalCodecPool.handleFor(inner.getClass()) == null) {
                    collectSlots(root, inner, out, cap, oracle, depth + 1);
                }
            }
        }
    }

    // ==================== 发现与匹配 ====================

    /** 候选外部藏血 Map：字段句柄（含声明类，写入口兜底要按类找方法）+ 当前真正挂在字段上的 Map。 */
    private static final class Candidate {
        final Class<?> owner;
        final Map<?, ?> map;

        Candidate(Class<?> owner, Map<?, ?> map) {
            this.owner = owner;
            this.map = map;
        }
    }

    /**
     * 候选外部藏血 Map：字段判据（静态 + {@code Map} + K 是实体身份型）由 {@link HealthDiscovery}
     * 全类路径枚举一次并缓存<b>字段句柄</b>；这里每次现读字段值（第三方换 Map 实例后不会读旧实例）。
     * 字段声明类同时就是「持有该 Map 的类」，静态写入口兜底按它找。
     */
    private static List<Candidate> candidateMaps(LivingEntity entity) {
        HealthDiscovery.Snapshot snap = HealthDiscovery.current();
        if (snap == null) return java.util.Collections.emptyList();
        List<Candidate> out = new ArrayList<>();
        for (FieldHandle h : snap.externalKeyMaps()) {
            Object v = h.tryGetObject();
            if (v instanceof Map<?, ?> map) out.add(new Candidate(h.owner(), map));
        }
        return out;
    }

    /** 匹配结果：命中该实体的<b>键</b>与值（键要留着写回 Map 用）。 */
    private static final class Match {
        final Object key;
        final Object value;

        Match(Object key, Object value) {
            this.key = key;
            this.value = value;
        }
    }

    private static Match matchEntry(Map<?, ?> map, LivingEntity entity) {
        Object key = entity.getUUID();
        Object v = safeGet(map, key);
        if (v != null) return new Match(key, v);
        key = entity.getId();
        v = safeGet(map, key);
        if (v != null) return new Match(key, v);
        v = safeGet(map, entity);
        if (v != null) return new Match(entity, v);
        try {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                Object k = e.getKey();
                if (k instanceof WeakReference<?> wr && wr.get() == entity) return new Match(k, e.getValue());
            }
        } catch (Throwable ignored) {}
        // 键不是实体身份形态时：按「值里持有该实体身份」做有界兜底（小表才扫）
        try {
            if (map.size() > 0 && map.size() <= 512) {
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    if (e.getValue() != null && holdsEntity(e.getValue(), entity)) {
                        return new Match(e.getKey(), e.getValue());
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static Object safeGet(Map<?, ?> map, Object key) {
        try {
            return map.get(key);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 值对象是否持有该实体的身份（实体引用 / 弱引用 / UUID / UUID 串 / 数值 id）。 */
    private static boolean holdsEntity(Object obj, LivingEntity entity) {
        String uuidStr = null;
        for (Field f : instanceFields(obj.getClass())) {
            try {
                Object v = f.get(obj);
                if (v == null) continue;
                if (v == entity) return true;
                if (v instanceof WeakReference<?> wr && wr.get() == entity) return true;
                if (v instanceof java.util.UUID u) {
                    if (u.equals(entity.getUUID())) return true;
                } else if (v instanceof String s) {
                    if (uuidStr == null) uuidStr = entity.getStringUUID();
                    if (s.equals(uuidStr)) return true;
                } else if (v instanceof Integer i) {
                    if (i == entity.getId()) return true;
                }
            } catch (Throwable ignored) {}
        }
        return false;
    }

    // ==================== 工具 ====================

    /**
     * 实体血量读数（判定基准）：<b>优先用已定位到的真实血量槽</b>（模组把 getHealth 做成
     * 显示值/软压值时它才是真值），定位不到才退回 {@code getHealth()}。
     * 这样「判定基准」就不再绑死在原版 API 上 —— 谁说的是真话，用谁。
     */
    private static Double oracle(LivingEntity entity) {
        try {
            Double located = EntityHealthLocator.readLocated(entity);
            if (located != null && Double.isFinite(located)) return located;
        } catch (Throwable ignored) {}
        try {
            double h = entity.getHealth();
            return Double.isFinite(h) ? h : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 上限：优先原版属性。属性被伪造成固定值/无意义值时（返回 NaN/≤0），退回「持有该 Map 的类上的
     * {@code (实体) -> 数值} 读出口」里最大的那个当上限 —— 上限是这类读出口里唯一必然 ≥ 当前血的量。
     */
    private static double capOf(LivingEntity entity, Class<?> owner) {
        try {
            double m = entity.getMaxHealth();
            if (Double.isFinite(m) && m > 0) return m;
        } catch (Throwable ignored) {}
        if (owner != null) {
            double best = Double.NaN;
            for (Method r : ExternalCodecPool.entityReadersOf(owner)) {
                Double v = ExternalCodecPool.callEntityReader(r, entity);
                if (v == null || !Double.isFinite(v) || v <= 0) continue;
                if (Double.isNaN(best) || v > best) best = v;
            }
            if (!Double.isNaN(best)) return best;
        }
        return Double.NaN;
    }

    /** 近似相等（绝对 0.5 或相对 0.1%）。 */
    private static boolean near(double a, double b) {
        return Math.abs(a - b) <= Math.max(0.5, Math.max(Math.abs(a), Math.abs(b)) * 0.001);
    }

    private static boolean isNumeric(Class<?> t) {
        return t == float.class || t == double.class || t == int.class || t == long.class
                || t == short.class || t == byte.class;
    }

    /** 值类型（不可作为载体）：原始类型/包装/字符串/枚举/JDK/引擎/自家。 */
    private static boolean isValueType(Class<?> t) {
        if (t.isPrimitive() || t.isEnum() || t == String.class) return true;
        if (Number.class.isAssignableFrom(t) || CharSequence.class.isAssignableFrom(t)) return true;
        String n = t.getName();
        return n.startsWith("java.") || n.startsWith("javax.") || n.startsWith("jdk.") || n.startsWith("sun.")
                || n.startsWith("com.mojang.") || n.startsWith("net.minecraft.")
                || n.startsWith("net.minecraftforge.") || n.startsWith("org.spongepowered.")
                || n.startsWith("io.netty.") || n.startsWith("it.unimi.") || n.startsWith("org.slf4j.");
    }

    private static boolean skipForWrite(Field f) {
        int m = f.getModifiers();
        return Modifier.isStatic(m) || f.isSynthetic();
    }

    private static final Map<Class<?>, List<Field>> INSTANCE_FIELDS = new ConcurrentHashMap<>();

    /** 实例字段清单按类缓存（含继承链；只缓存句柄，值每次现读）。 */
    private static List<Field> instanceFields(Class<?> clazz) {
        return INSTANCE_FIELDS.computeIfAbsent(clazz, c -> {
            List<Field> list = new ArrayList<>();
            for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
                for (Field f : k.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    try {
                        f.setAccessible(true);
                    } catch (Throwable ignored) {}
                    list.add(f);
                }
            }
            return List.copyOf(list);
        });
    }

    private static Object get(Object holder, Field f) {
        try {
            return f.get(holder);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Double number(Object holder, Field f) {
        try {
            Class<?> t = f.getType();
            if (!isNumeric(t)) return null;
            double v = f.getDouble(holder);
            return Double.isFinite(v) ? v : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean setNumber(Object holder, Field f, double v) {
        try {
            Class<?> t = f.getType();
            if (t == float.class) f.setFloat(holder, (float) v);
            else if (t == double.class) f.setDouble(holder, v);
            else if (t == long.class) f.setLong(holder, Math.round(v));
            else if (t == int.class) f.setInt(holder, (int) Math.round(v));
            else if (t == short.class) f.setShort(holder, (short) Math.round(v));
            else if (t == byte.class) f.setByte(holder, (byte) Math.round(v));
            else return false;
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean setObject(Object holder, Field f, Object value) {
        try {
            f.set(holder, value);
            return true;
        } catch (Throwable t) {
            try {
                sun.misc.Unsafe u = net.minecraft.client.yiz.tool.key.UnsafeAccess.get();
                if (u == null) return false;
                u.putObject(holder, u.objectFieldOffset(f), value);
                return true;
            } catch (Throwable t2) {
                return false;
            }
        }
    }

    private static void logOnce(LivingEntity entity, Object entry, String how) {
        String key = entity.getClass().getName();
        if (LOGGED.add(key)) {
            LOGGER.info("[ExtStore] {} 命中外部藏血 Map {} → {}（条目 {}）",
                    key, entry.getClass().getSimpleName(), how, entry.getClass().getName());
        }
    }
}
