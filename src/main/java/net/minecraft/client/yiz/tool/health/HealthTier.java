package net.minecraft.client.yiz.tool.health;

import net.minecraft.client.yiz.tizMod;
import net.minecraft.world.entity.LivingEntity;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 发现分层策略：<b>常规实体缓存，非常规生命值实体一律现场扫描</b>。
 *
 * <p><b>要解决的问题</b>：外部藏血发现（三处发现器 + 可达对象图）按「全类路径候选 × 逐字段遍历」
 * 工作，一次调用就是几千个候选对象的字段走查。绝大多数实体血量规规矩矩躺在 vanilla 通道里，
 * 每次攻击都为它们走一遍这套普查纯属浪费；而真正把血藏起来的实体（藏血 Map / 差值血量 /
 * 加密串 / 外部存档 / 权威门控）又恰恰是<b>绝不能信缓存</b>的那批——它们的承载对象会被换壳、
 * Map 会被替换、拉回程序每 tick 在跑，拿旧解析结果去写就是「改不动」。</p>
 *
 * <p>所以按类分两层：</p>
 * <ul>
 *   <li><b>常规实体</b>：跳过外部藏血发现（省掉候选走查），只走主槽判定 + 廉价镜像通道；
 *       判定<b>按类缓存</b>，{@link #REGULAR_RECHECK_MS} 到期或类结构指纹变化即作废复验。</li>
 *   <li><b>非常规生命值实体</b>：<b>一律现场扫描</b>，每次攻击都重跑发现（字段句柄仍复用，
 *       但候选集与字段值都是当场重新取），判定<b>粘性</b>、永不降级。</li>
 * </ul>
 *
 * <h3>匹配策略（怎么「正常分辨」两类）</h3>
 * <p>判定只认<b>行为证据</b>，不认类名/包名/模组名；默认是「未知」，未知一律按现场全量探测走：</p>
 * <ul>
 *   <li><b>判为非常规</b>（任一命中即粘性成立）：行为定位到主槽（{@code EntityHealthLocator}）／
 *       任一藏血发现器读到值／差值血量 FSUB／2 tick 写回被拉回／立即回读未落地／命中权威门控。</li>
 *   <li><b>判为常规</b>（必须正向证据，且只从「未知」升级）：
 *       {@link GateHunt} 的 2 tick 写回验证确认「值保持住了」或「实体已被本次写入击杀」——
 *       两条都发生在<b>立即回读已经落地</b>之后，即「写进去 + 没被拉回」的双重行为证明。</li>
 *   <li><b>作废</b>：常规判定被任一异常信号打回「未知」，下次攻击重新现场全量探测。</li>
 * </ul>
 * <p>换句话说：<b>「常规」是可以被证伪的乐观结论，「非常规」是不可推翻的结论</b>——
 * 宁可多扫，绝不少扫。</p>
 */
public final class HealthTier {

    private static final Logger LOGGER = tizMod.LOGGER;

    /** 常规判定的复验周期：到期后这一刀按现场全量探测走一遍（顺带比对结构指纹）。 */
    private static final long REGULAR_RECHECK_MS = 30_000L;

    public enum Kind { UNKNOWN, REGULAR, IRREGULAR }

    private static final class Verdict {
        volatile Kind kind = Kind.UNKNOWN;
        volatile long lastLiveMs;
        volatile long fingerprint;
        volatile String reason = "";
    }

    private static final Map<Class<?>, Verdict> VERDICTS = new ConcurrentHashMap<>();
    /** 日志去重键（每类每种结论只打一次，避免刷屏）。 */
    private static final Set<String> LOGGED = ConcurrentHashMap.newKeySet();

    private HealthTier() {}

    // ==================== 主入口 ====================

    /**
     * 本次攻击要不要走「现场扫描」的外部发现通道。
     *
     * @return {@code true} = 现场扫描（未知 / 非常规 / 常规到期复验）；{@code false} = 常规缓存命中，
     *         跳过三处外部藏血发现（主槽判定与廉价镜像通道不受影响）。
     */
    public static boolean liveScan(LivingEntity entity) {
        if (entity == null) return true;
        Class<?> cls = entity.getClass();
        Verdict v = VERDICTS.computeIfAbsent(cls, c -> new Verdict());
        long now = System.currentTimeMillis();
        switch (v.kind) {
            case IRREGULAR:
                // 非常规生命值实体：一律现场扫描，永不缓存
                v.lastLiveMs = now;
                return true;
            case REGULAR: {
                if (now - v.lastLiveMs < REGULAR_RECHECK_MS) return false;
                v.lastLiveMs = now;
                // 到期复验：结构指纹变了说明类被换壳/模组更新 → 作废常规判定，按现场全量探测走
                long fp = fingerprint(cls);
                if (v.fingerprint != 0L && fp != v.fingerprint) {
                    LOGGER.warn("[HealthTier] {} 类结构指纹变化 → 作废常规判定，回到现场全量扫描",
                            cls.getName());
                    v.kind = Kind.UNKNOWN;
                    v.reason = "结构指纹变化";
                }
                return true;
            }
            default:
                // 未知：第一次遇到（或刚被降级）→ 现场全量探测，这一刀兼作判据采样
                v.lastLiveMs = now;
                return true;
        }
    }

    // ==================== 结论登记 ====================

    /** 登记「非常规生命值实体」：粘性，一旦成立不再降级（非常规永远现场扫描）。 */
    public static void markIrregular(Class<?> cls, String reason) {
        if (cls == null) return;
        try {
            Verdict v = VERDICTS.computeIfAbsent(cls, c -> new Verdict());
            Kind old = v.kind;
            v.kind = Kind.IRREGULAR;
            if (old != Kind.IRREGULAR) {
                v.reason = reason;
                logOnce("irr:" + cls.getName(),
                        "[HealthTier] {} → 非常规生命值实体（每次攻击现场全量扫描）: {}",
                        cls.getName(), reason);
            }
        } catch (Throwable ignored) {}
    }

    /**
     * 登记「常规实体」：只从「未知」升级，且需要正向行为证据（写回落地 + 未被拉回）。
     * 已判非常规的类不会被覆盖。
     */
    public static void markRegular(Class<?> cls, String reason) {
        if (cls == null) return;
        try {
            Verdict v = VERDICTS.computeIfAbsent(cls, c -> new Verdict());
            if (v.kind != Kind.UNKNOWN) return;          // 非常规不可覆盖；已是常规无需重复
            v.kind = Kind.REGULAR;
            v.reason = reason;
            v.fingerprint = fingerprint(cls);
            logOnce("reg:" + cls.getName(),
                    "[HealthTier] {} → 常规实体（跳过外部藏血发现，{}s 复验一次）: {}",
                    cls.getName(), REGULAR_RECHECK_MS / 1000L, reason);
        } catch (Throwable ignored) {}
    }

    /** 作废判定（常规 → 未知）：下次攻击重新现场全量探测。非常规不受影响。 */
    public static void demote(Class<?> cls, String reason) {
        if (cls == null) return;
        try {
            Verdict v = VERDICTS.get(cls);
            if (v == null || v.kind != Kind.REGULAR) return;
            v.kind = Kind.UNKNOWN;
            v.lastLiveMs = 0L;
            v.reason = reason;
            LOGGER.warn("[HealthTier] {} 常规判定作废 → 回到现场全量扫描: {}", cls.getName(), reason);
        } catch (Throwable ignored) {}
    }

    /** 当前结论（诊断用）。 */
    public static Kind kindOf(Class<?> cls) {
        Verdict v = cls == null ? null : VERDICTS.get(cls);
        return v == null ? Kind.UNKNOWN : v.kind;
    }

    // ==================== 结构指纹 ====================

    /**
     * 类结构指纹：继承链上全部字段的「名字 + 类型 + 修饰符」。
     *
     * <p>用来发现「同一个 Class 对象被换壳」——外部 agent/ASM 在会话中途给实体类补藏血字段时，
     * 类对象不变但字段集合会变，指纹对不上就把常规判定作废。</p>
     */
    private static long fingerprint(Class<?> cls) {
        long h = 1125899906842597L;
        try {
            for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
                h = h * 31 + c.getName().hashCode();
                for (Field f : c.getDeclaredFields()) {
                    h = h * 31 + f.getName().hashCode();
                    h = h * 31 + f.getType().getName().hashCode();
                    h = h * 31 + f.getModifiers();
                }
            }
        } catch (Throwable ignored) {}
        return h == 0L ? 1L : h;
    }

    private static void logOnce(String key, String fmt, Object... args) {
        if (LOGGED.add(key)) LOGGER.info(fmt, args);
    }
}
