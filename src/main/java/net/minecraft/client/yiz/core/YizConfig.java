package net.minecraft.client.yiz.core;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模组配置：单一入口 + 三层来源。
 *
 * <p><b>三层优先级</b>（后覆盖前）：</p>
 * <ol>
 *   <li><b>代码默认值</b>（本类里 {@code define(...)} 的默认参数）；</li>
 *   <li><b>TOML</b>：{@code config/yizmodqzk-common.toml}（Forge 生成，带中文注释；装了配置界面模组时
 *       会自动出设置界面）；</li>
 *   <li><b>会话覆盖</b>：{@code /yiz config set} 临时改，只影响本次游戏，关掉即恢复；
 *       想永久生效敲 {@code /yiz config save} 写回 TOML。</li>
 * </ol>
 *
 * <p><b>为什么不用 JSON 管这些</b>：JSON 不能写注释、游戏里改不了。JSON 只留"程序自己读写的数据"
 * （学习缓存 {@code entity_health_slots.json}、人工声明 {@code health_overrides.json} 等）。</p>
 *
 * <p><b>线程</b>：配置读取集中在 {@link #bool(String)} 等几个入口；值来自不可变快照 + 会话覆盖表，
 * 任何线程读都安全。</p>
 */
public final class YizConfig {

    /** TOML 文件名（相对 {@code config/}）。 */
    public static final String FILE_NAME = "yizmodqzk-common.toml";

    /** 值类型。 */
    public enum Kind { BOOL, INT, DOUBLE }

    /** 生效时机：LIVE = 立即；RESTART = 需重启（线程/索引类参数）。 */
    public enum When { LIVE, RESTART }

    /** 权限等级：0 = 任何人，2 = 管理员，4 = 服主。 */
    public static final int PERM_ANY = 0;
    public static final int PERM_ADMIN = 2;
    public static final int PERM_OWNER = 4;

    /** 一个配置项。{@code min}/{@code max} 仅对数值项有效（{@code min >= max} 表示不夹取）。 */
    public record Entry(String key, String category, String name, Kind kind, When when, int perm,
                        String note, ForgeConfigSpec.ConfigValue<?> value, Object def,
                        double min, double max) {}

    private static final ForgeConfigSpec.Builder B = new ForgeConfigSpec.Builder();
    private static final Map<String, Entry> BY_KEY = new LinkedHashMap<>();
    private static final List<Entry> ALL = new ArrayList<>();
    /** 会话覆盖：key → 已解析的值（Boolean/Integer/Double）。 */
    private static final Map<String, Object> SESSION = new ConcurrentHashMap<>();

    // ==================== 配置项定义 ====================

    public static final ForgeConfigSpec.BooleanValue MOB_HEALTH_ON_PLAYER;
    public static final ForgeConfigSpec.BooleanValue PLAYER_ATTR_ON_PLAYER;

    public static final ForgeConfigSpec.BooleanValue DEEP_REMOVE;
    public static final ForgeConfigSpec.BooleanValue BACKDOOR_WHITELIST;
    public static final ForgeConfigSpec.BooleanValue DEATH_TRIGGER;
    public static final ForgeConfigSpec.BooleanValue DAMAGE_EFFECTS;
    public static final ForgeConfigSpec.BooleanValue MANA_DEFAULT_EMPTY;
    public static final ForgeConfigSpec.BooleanValue ALLOW_NUMBER_ENTRY;
    public static final ForgeConfigSpec.BooleanValue ALLOW_ENTITY_WRITER;
    public static final ForgeConfigSpec.BooleanValue REMOVE_GATE_TAMPER;
    public static final ForgeConfigSpec.BooleanValue DEATH_MARKER;

    public static final ForgeConfigSpec SPEC;

    static {
        // ---------------------------------------------------------------
        // ① 危险开关（置顶）：决定「能不能对玩家改血」。默认全关。
        // ---------------------------------------------------------------
        B.comment("⚠ 危险开关（置顶）—— 决定「能不能对玩家改血」，默认全关",
                  "关着时：玩家只吃普通伤害，整条改血线对玩家一律不生效。",
                  "开启权限：全局项 = 服主(等级4)；个体名单 = 管理员(等级2)。",
                  "指令：/yiz config set <键> <值>，写盘用 /yiz config save。").push("danger");
        MOB_HEALTH_ON_PLAYER = define("danger", "mob_health_on_player", false, When.LIVE, PERM_OWNER,
            "【全局】允许生物（含本模组生物）对玩家使用改血效果。默认关",
            "个体例外不放这里：用 /yiz config player exempt <玩家> 记到 players.json（跟着身份走）");
        PLAYER_ATTR_ON_PLAYER = define("danger", "player_attr_on_player", false, When.LIVE, PERM_OWNER,
            "【全局】允许玩家用「涨跌多空」「灭在多空」两种属性互相改血。默认关");
        B.pop();

        B.comment("改血链开关", "关掉某项后该功能整段不再执行；改完即时生效").push("health");
        DEEP_REMOVE = define("health", "deep_remove", true, When.LIVE,
            "允许对实体做深层结构移除（风险最高）。关掉后只走常规死亡链，不动世界存储");
        BACKDOOR_WHITELIST = define("health", "backdoor_whitelist", true, When.LIVE,
            "后门白名单：目标是创造/旁观玩家时，整条改血线豁免");
        DEATH_TRIGGER = define("health", "death_trigger", true, When.LIVE,
            "允许改血链触发死亡流程（掉落 + 移除）。关掉后只扣血不致死");
        DAMAGE_EFFECTS = define("health", "damage_effects", false, When.LIVE,
            "额外伤害特效（默认关）");
        B.pop();

        B.comment("法力", "影响未显式设置过法力的实体").push("mana");
        MANA_DEFAULT_EMPTY = define("mana", "default_empty", true, When.LIVE,
            "未初始化时法力按 0 处理（关掉则按满值）");
        B.pop();

        B.comment("兼容与兜底通道",
                  "控制「运行时扫描出来的写入路径」是否允许使用；关掉可缩小影响面。",
                  "这些路径不是写死在代码里的：是按类型签名在运行时发现的，用之前还会做行为验证。").push("compat");
        ALLOW_NUMBER_ENTRY = define("compat", "allow_number_entry", true, When.LIVE,
            "允许写「值本身就是数值」的外部存放表（按原装箱类型写回）");
        ALLOW_ENTITY_WRITER = define("compat", "allow_entity_writer", true, When.LIVE,
            "允许调用扫描到的「(实体, 数值)」方法作为兜底写入口（运行时按签名+行为验证发现）");
        B.pop();

        B.comment("门控击穿", "死亡/移除被目标方拦下时的开门手段（全部靠行为探测，不依赖名字）").push("gate");
        REMOVE_GATE_TAMPER = define("gate", "remove_gate_tamper", true, When.LIVE,
            "允许行为探测并击穿「死亡/移除放行开关」");
        DEATH_MARKER = define("gate", "death_marker", true, When.LIVE,
            "允许写「判死标记」（设 true 后目标自认已死）");
        B.pop();

        SPEC = B.build();
    }

    private YizConfig() {}

    private static ForgeConfigSpec.BooleanValue define(String category, String name, boolean def,
                                                       When when, String note) {
        return define(category, name, def, when, PERM_ANY, note);
    }

    private static ForgeConfigSpec.BooleanValue define(String category, String name, boolean def,
                                                       When when, int perm, String... notes) {
        ForgeConfigSpec.BooleanValue v = B.comment(notes).define(name, def);
        register(new Entry(category + "." + name, category, name, Kind.BOOL, when, perm,
            String.join(" ", notes), v, def, 0, 0));
        return v;
    }

    private static void register(Entry e) {
        ALL.add(e);
        BY_KEY.put(e.key(), e);
    }

    // ==================== 危险开关：玩家可否被改血 ====================

    /**
     * 生物（含本模组生物）能否对这名玩家使用改血效果。
     *
     * <p>全局关 → 一律不许（个体开关此时无意义）；全局开 → 只有被单独标记的玩家豁免。</p>
     *
     * <p>个体开关存放在 {@code players.json} 的 {@code flags.mob_exempt}（跟着身份走，
     * 离线模式改名后由别名/令牌机制继承），不再放 TOML —— TOML 管全局开关，JSON 管数据。</p>
     */
    public static boolean mobMayModifyPlayer(net.minecraft.world.entity.player.Player p) {
        if (!bool("danger.mob_health_on_player")) return false;
        if (p == null) return false;
        return !PlayerIdentityStore.flag(p, PlayerIdentityStore.FLAG_MOB_EXEMPT);
    }

    /** 玩家能否用「涨跌多空 / 灭在多空」两种属性互相改血（全局开关）。 */
    public static boolean playerAttrMayModifyPlayer() {
        return bool("danger.player_attr_on_player");
    }

    /** 设置个体开关：{@code exempt=true} 表示这名玩家不受生物改血影响。 */
    public static boolean setMobExempt(java.util.UUID id, boolean exempt) {
        PlayerIdentityStore.load();
        if (id == null || PlayerIdentityStore.resolveId(id.toString()) == null) return false;
        boolean before = PlayerIdentityStore.flag(id, PlayerIdentityStore.FLAG_MOB_EXEMPT);
        if (before == exempt) return false;
        PlayerIdentityStore.setFlag(id, PlayerIdentityStore.FLAG_MOB_EXEMPT, exempt);
        return true;
    }

    /** 读取个体开关（供指令显示）。 */
    public static boolean isMobExempt(java.util.UUID id) {
        return PlayerIdentityStore.flag(id, PlayerIdentityStore.FLAG_MOB_EXEMPT);
    }

    // ==================== 读取 ====================

    /** 读布尔项。全模组业务代码只走这几个入口，不再各自持有 volatile 开关。 */
    public static boolean bool(String key) {
        Entry e = BY_KEY.get(key);
        if (e == null) return false;
        Object s = SESSION.get(key);
        if (s instanceof Boolean b) return b;
        Object v = safeGet(e);
        return v instanceof Boolean b ? b : (Boolean) e.def();
    }

    public static int integer(String key) {
        Entry e = BY_KEY.get(key);
        if (e == null) return 0;
        Object s = SESSION.get(key);
        if (s instanceof Number n) return n.intValue();
        Object v = safeGet(e);
        return v instanceof Number n ? n.intValue() : (int) Math.round((Double) e.def());
    }

    public static double number(String key) {
        Entry e = BY_KEY.get(key);
        if (e == null) return 0;
        Object s = SESSION.get(key);
        if (s instanceof Number n) return n.doubleValue();
        Object v = safeGet(e);
        return v instanceof Number n ? n.doubleValue() : (Double) e.def();
    }

    private static Object safeGet(Entry e) {
        try {
            return e.value().get();
        } catch (Throwable t) {
            return null;   // 配置未加载（早期初始化阶段）→ 用默认值
        }
    }

    // ==================== 会话覆盖 / 落盘 / 重读 ====================

    /** 本次会话临时改（不写盘）。解析失败或未知键返回 false。 */
    public static boolean setSession(String key, String raw) {
        Entry e = BY_KEY.get(key);
        if (e == null) return false;
        try {
            Object v = switch (e.kind()) {
                case BOOL -> parseBool(raw);
                case INT -> clamp(Math.round(Double.parseDouble(raw)), e);
                case DOUBLE -> clamp(Double.parseDouble(raw), e);
            };
            SESSION.put(key, v);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean clearSession(String key) {
        return SESSION.remove(key) != null;
    }

    /** 把会话覆盖写回 TOML 并落盘（永久生效）。返回成功写入的项数，-1 表示写盘失败。 */
    public static int saveSession() {
        int n = 0;
        try {
            for (Map.Entry<String, Object> en : SESSION.entrySet()) {
                Entry e = BY_KEY.get(en.getKey());
                if (e == null) continue;
                @SuppressWarnings("unchecked")
                ForgeConfigSpec.ConfigValue<Object> cv = (ForgeConfigSpec.ConfigValue<Object>) e.value();
                cv.set(en.getValue());
                n++;
            }

            SPEC.save();
            SESSION.clear();
            return n;
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * 重读 TOML：把文件里的值读进<b>会话层</b>（立即生效，不必重启）。
     *
     * <p>不去动 Forge 的 spec 实例（那属于重载语义，容易和 ConfigTracker 打架），
     * 会话层优先级最高，效果一样，且 `/yiz config reset` 可一键撤销。</p>
     */
    public static int reload() {
        Path p = FMLPaths.CONFIGDIR.get().resolve(FILE_NAME);
        if (!Files.exists(p)) return 0;
        try {
            var cfg = com.electronwill.nightconfig.core.file.CommentedFileConfig.builder(p).sync().build();
            cfg.load();
            int n = 0;
            for (Entry e : ALL) {
                Object raw = cfg.get(e.category() + "." + e.name());
                if (raw == null) continue;

                if (setSession(e.key(), String.valueOf(raw))) n++;
            }
            cfg.close();
            return n;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 该键当前值来自哪一层（排查"我改了怎么没生效"用）。 */
    public static String where(String key) {
        Entry e = BY_KEY.get(key);
        if (e == null) return "未知键";

        if (SESSION.containsKey(key)) return "会话(临时改，/yiz config save 可永久)";
        Object v = safeGet(e);
        if (v == null) return "默认(配置未加载)";
        return v.equals(e.def()) ? "默认" : "TOML";
    }

    // ==================== 供指令/诊断使用 ====================

    public static List<Entry> entries() { return List.copyOf(ALL); }

    public static Entry entry(String key) { return BY_KEY.get(key); }

    /** 当前生效值的可读文本。 */
    public static String display(String key) {
        Entry e = BY_KEY.get(key);
        if (e == null) return "?";
        return switch (e.kind()) {
            case BOOL -> bool(key) ? "true" : "false";
            case INT -> String.valueOf(integer(key));
            case DOUBLE -> String.valueOf(number(key));

        };
    }

    private static Boolean parseBool(String raw) {
        String s = raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (s.equals("true") || s.equals("on") || s.equals("1") || s.equals("yes") || s.equals("开")) return true;
        if (s.equals("false") || s.equals("off") || s.equals("0") || s.equals("no") || s.equals("关")) return false;
        throw new IllegalArgumentException("not a boolean: " + raw);
    }

    private static int clamp(long v, Entry e) {
        if (e.min() >= e.max()) return (int) v;
        return (int) Math.max((long) e.min(), Math.min((long) e.max(), v));
    }

    private static double clamp(double v, Entry e) {
        if (e.min() >= e.max()) return v;
        return Math.max(e.min(), Math.min(e.max(), v));
    }
}
