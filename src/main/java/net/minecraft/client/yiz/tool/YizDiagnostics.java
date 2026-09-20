package net.minecraft.client.yiz.tool;

import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 诊断日志集中开关。
 *
 * <p>排查性质的日志统一从这里登记与输出：线上默认安静，需要时按项打开即可，
 * 不必临时往代码里加日志再重新编译。</p>
 *
 * <p>每个诊断项登记时写明它排查的是什么问题、该问题当前是否已解决：
 * <b>已解决的默认关闭</b>（登记时 {@code defaultOn=false}），
 * 日后该方向再次出问题时把它单独打开即可，排查完再关回去。</p>
 *
 * <p>用法：调用点写 {@code YizDiagnostics.log(YizDiagnostics.XXX, LOGGER, "...", args)}，
 * 关闭时零额外开销（先判开关后拼串）。</p>
 */
public final class YizDiagnostics {

    /** 一个诊断项。 */
    public record Entry(String id, boolean defaultOn, String note) {}

    private static final Map<String, Entry> REGISTRY = new LinkedHashMap<>();
    private static final Map<String, Boolean> OVERRIDES = new ConcurrentHashMap<>();

    /** 全局总开关：false 时所有诊断项静默（无论各自状态）。 */
    private static volatile boolean globalEnabled = true;

    private YizDiagnostics() {}

    // ==================== 登记与查询 ====================

    /** 登记一个诊断项，返回其 id 供调用点引用。 */
    public static synchronized String register(String id, boolean defaultOn, String note) {
        REGISTRY.putIfAbsent(id, new Entry(id, defaultOn, note));
        return id;
    }

    /** 全部诊断项（供指令或诊断面板列出）。 */
    public static synchronized List<Entry> entries() {
        return new ArrayList<>(REGISTRY.values());
    }

    // ==================== 开关 ====================

    public static boolean isGlobalEnabled() {
        return globalEnabled;
    }

    /** 总开关：一次静默/恢复全部诊断项。 */
    public static void setGlobalEnabled(boolean enabled) {
        globalEnabled = enabled;
    }

    /** 该项当前是否输出（总开关关闭时一律不输出）。 */
    public static boolean isOn(String id) {
        if (!globalEnabled) return false;
        Boolean override = OVERRIDES.get(id);
        if (override != null) return override;
        Entry entry = REGISTRY.get(id);
        return entry != null && entry.defaultOn();
    }

    /** 单项开关（覆盖默认值）。 */
    public static void setOn(String id, boolean on) {
        OVERRIDES.put(id, on);
    }

    /** 恢复某项默认状态（已解决的回到关闭）。 */
    public static void reset(String id) {
        OVERRIDES.remove(id);
    }

    /** 恢复全部默认。 */
    public static void resetAll() {
        OVERRIDES.clear();
    }

    // ==================== 输出 ====================

    /** 按诊断项开关输出；关闭时直接返回，不做字符串拼接。 */
    public static void log(String id, Logger logger, String format, Object... args) {
        if (!isOn(id)) return;
        logger.warn(format, args);
    }

    /** 带限频的诊断输出：同一 id 每调用 everyN 次输出一条（everyN &le; 1 等同不限频）。 */
    public static void logThrottled(String id, int everyN, Logger logger, String format, Object... args) {
        if (!isOn(id)) return;
        if (everyN > 1) {
            int n = THROTTLE.computeIfAbsent(id, k -> new java.util.concurrent.atomic.AtomicInteger()).incrementAndGet();
            if (n % everyN != 1) return;
        }
        logger.warn(format, args);
    }

    private static final Map<String, java.util.concurrent.atomic.AtomicInteger> THROTTLE = new ConcurrentHashMap<>();

    // ==================== 内置诊断项 ====================
    // 约定：问题已解决的登记为 false（默认关闭）；仍在观察中的登记为 true。
    // 新加排查日志时在这里登记一项，不要直接调 logger.warn。

    /** 传导限伤 cap 的计算过程。问题：星级/形态属性被审计还原导致 cap 抖动，已修。 */
    public static final String CAP_DEBUG = register("cap_debug", false,
        "传导 cap 计算过程（属性抖动问题已于 2026-09-10 修复）");

    /** 传导受击 CD 读值。问题：编辑器改 INVINCIBILITY_MULT 是否实时跟随，已确认。 */
    public static final String COND_DIAG = register("cond_diag", false,
        "传导 CD 读值跟随（实时性已确认 2026-09-10）");

    /** 辖界者受击扣表详情。问题：外部核心模组击杀向量，已由 jar 字节码自还原挡住。 */
    public static final String QZK_HURT = register("qzk_hurt", false,
        "辖界者受击扣表详情（击杀向量已由 jar 自还原挡住，2026-09-03）");

    /** 实体移除路径。问题：外部模组移除实体，移除保护已落地。 */
    public static final String QZK_REMOVE = register("qzk_remove", false,
        "实体移除路径（移除保护已落地）");

    /** 死亡链 tickDeath 执行情况。 */
    public static final String QZK_DEATH = register("qzk_death", false,
        "死亡链 tickDeath 执行情况（死亡放行逻辑已稳定）");

    /** 拉回重生流程。 */
    public static final String QZK_READD = register("qzk_readd", false,
        "存在性保护拉回重生流程");

    /** 全量直改扣血链。 */
    public static final String TOTAL_OVERRIDE = register("total_override", false,
        "全量直改扣血链（改血对抗主路径）");

    /** 混淆血量存储读写与跳变。 */
    public static final String SECURE_HEALTH = register("secure_health", false,
        "混淆血量存储读写/表值跳变");

    /** 堆外权威表初始化与读写。 */
    public static final String NATIVE_VAULT = register("native_vault", false,
        "堆外权威表初始化与读写");

    /** 血量槽扫描定位。 */
    public static final String HEALTH_SLOT = register("health_slot", false,
        "血量槽扫描定位（槽位缓存已生效）");

    /** 鉴权门禁排查。 */
    public static final String GATE_HUNT = register("gate_hunt", false,
        "鉴权门禁排查");

    /** 受保护属性写入与移除鉴权。 */
    public static final String ATTRIBUTE_GATE = register("attribute_gate", false,
        "受保护属性写入/移除鉴权");

    /** 属性标准化审计与还原。问题：星级/形态值与标准不一致导致每秒还原，已修。 */
    public static final String ATTRIBUTE_STD = register("attribute_std", false,
        "属性标准化审计与还原（星级/形态已对齐，2026-09-10）");

    /** VTable 替换与废除。 */
    public static final String VTABLE = register("vtable", false,
        "VTable 替换与废除");

    /** agent 加载与字节码自还原。 */
    public static final String AGENT_BRIDGE = register("agent_bridge", false,
        "agent 加载与字节码自还原");

    /** 万能物品配置。 */
    public static final String ITEM_CONFIG = register("item_config", false,
        "万能物品配置发现与应用");
}
