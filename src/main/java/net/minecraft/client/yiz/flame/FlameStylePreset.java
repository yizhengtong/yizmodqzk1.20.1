package net.minecraft.client.yiz.flame;

import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * <b>火焰观感预设</b> —— 一组可以直接在游戏里切换的名字（{@code /yiz flame preset <名字>}），
 * 每个名字对应"一整套观感数值"。
 *
 * <h2>默认档与"可运行时切换"的分工</h2>
 * <ul>
 *   <li>默认档 = 本类里 {@link #CLASSIC} 那一行常量（改它要重新编译）；</li>
 *   <li>本类 = <b>可运行时切换的那一层</b>：只动"每帧都会喂给着色器的 uniform"，
 *       所以切完立刻能看见效果（已经烧起来的贴模型火会当帧变化）。</li>
 * </ul>
 *
 * <h2>为什么只动 uniform，不动着色器里的 #define</h2>
 * <p>"火焰多高"这件事由 {@code rendertype_flame_surface.fsh} 的 {@code SPREAD_*} 常量钉死，
 * 那是用户明确要求过的硬约束（<b>不超过目标身高的一半</b>）——它不该是某个预设能改掉的风格选项。
 * 所以所有预设<b>都在同一条高度规则下</b>换皮：粗细、快慢、浓淡、明暗、颜色、火舌长度可以换，
 * "烧到哪为止"不换。要改高度请改那两个常量（说明书里有对照表）。</p>
 *
 * <h2>加一个新预设</h2>
 * <p>在下面的预设表里加一行 {@code register(...)} 即可，指令补全与 {@code list} 会自动带上它
 * （别名走 {@link #ALIASES}，不要往预设表里塞别名，否则 {@code list} 会多出一条）。
 * 每个数只要想清楚"往大调会看到什么"（见说明书里的"旋钮 → 观感"表）。</p>
 */
public final class FlameStylePreset {

    // ────────────────────────── 字段（= 一组 uniform 值）──────────────────────────

    /** 指令里用的名字（小写、无空格）。 */
    public final String id;
    /** 中文名（指令回显与文档用）。 */
    public final String label;
    /** 一句话观感（指令回显用）。 */
    public final String blurb;

    /** 噪声尺度（uParams.x）：越大火舌越碎越细、条数越多。 */
    public final float noiseScale;
    /** 上升流速（uParams.y）：越大火苗窜得越快。 */
    public final float flow;
    /** 密度 / 每步吸收率（uParams.z）：越大越浓越"实"。 */
    public final float density;
    /** 湍流阈值（uParams.w）：越高火舌之间空隙越大、越斑驳。 */
    public final float turbulence;
    /** 透明度上限（uParams2.w）：越低越能透出本体颜色。 */
    public final float opacity;
    /** 火焰自身亮度（uBrightness）：只提亮、不变实。 */
    public final float brightness;

    /** 光程长度（格，uParams2.x）：从体表往外舔多远。 */
    public final float marchLength;
    /** 向外衰减指数（uParams3.z）：越大火舌越短越虚，越小拖尾越长。 */
    public final float falloffK;
    /** 轮廓处最低强度（uParams3.w）：越小轮廓（往外舔最远的那圈）越暗。 */
    public final float edgeFadeMin;

    /** 火星概率：每拍冒出的"短命小亮点"变成火星（而不是又一团主火焰）的概率。 */
    public final float sparkChance;

    /** 三段火色：核心（最热）→ 中层 → 外焰（最冷）。 */
    public final Vec3 core, mid, edge;

    private FlameStylePreset(String id, String label, String blurb,
                             float noiseScale, float flow, float density, float turbulence,
                             float opacity, float brightness,
                             float marchLength, float falloffK, float edgeFadeMin, float sparkChance,
                             Vec3 core, Vec3 mid, Vec3 edge) {
        this.id = id;
        this.label = label;
        this.blurb = blurb;
        this.noiseScale = noiseScale;
        this.flow = flow;
        this.density = density;
        this.turbulence = turbulence;
        this.opacity = opacity;
        this.brightness = brightness;
        this.marchLength = marchLength;
        this.falloffK = falloffK;
        this.edgeFadeMin = edgeFadeMin;
        this.sparkChance = sparkChance;
        this.core = core;
        this.mid = mid;
        this.edge = edge;
    }

    // ────────────────────────── 预设表 ──────────────────────────

    private static final Map<String, FlameStylePreset> ALL = new LinkedHashMap<>();

    /**
     * <b>经典/Classic（默认档）</b>：小而柔、偏白黄、轻微抖动、能看见身体材质。
     *
     * <p>这一行的每个数与"回退后的可见档"逐值相同 —— 也就是用户此前认可的那一版。
     * 切换预设后想回到原样就是它。</p>
     *
     * <p><b>数值来源</b>：原引自已归档的烛火特效（2026-10-29 归档到
     * {@code 废案\烛火-candle-fire-2026-10-29}），归档时写死为等价常量：
     * 噪声 4.4 / 流速 2.2 / 密度 5.0 / 湍流 0.62 / 透明度 0.85 / 亮度 1.0。</p>
     *
     * <p><b>改名记录</b>：本预设的 id 原为 {@code candle}（旧烛火功能的名字），2026-10-29 改为
     * {@code classic} / "经典/Classic" —— 只改名，<b>参数值一个都没动</b>。
     * 旧名 {@code candle} 保留为别名（见 {@link #ALIASES}），{@code /yiz flame preset candle} 仍可用。</p>
     */
    public static final FlameStylePreset CLASSIC = register(
            "classic", "经典/Classic", "小而柔、偏白黄、轻微抖动，隔着火能看见身体材质（默认档）",
            4.4f, 2.2f, 5.0f, 0.62f,
            0.85f, 1.0f,
            0.42f, 1.6f, 0.35f, 0.35f,
            FlameEffects.CORE, FlameEffects.MID, FlameEffects.EDGE);

    /** <b>火把</b>：细长、上窜快、核心更亮更黄。 */
    public static final FlameStylePreset TORCH = register(
            "torch", "火把", "细长上窜、条数多、核心亮黄（贴在身上像「被火把点着」）",
            6.2f, 3.4f, 6.4f, 0.55f,
            0.88f, 1.15f,
            0.40f, 1.15f, 0.30f, 0.35f,
            new Vec3(1.00, 0.97, 0.82), new Vec3(1.00, 0.60, 0.14), new Vec3(0.58, 0.10, 0.02));

    /** <b>营火</b>：火舌粗、慢、橙红为主、火星多。 */
    public static final FlameStylePreset CAMPFIRE = register(
            "campfire", "营火", "火舌粗而慢、橙红为主、火星多（像柴堆那种「闷烧」）",
            2.4f, 1.2f, 5.6f, 0.44f,
            0.82f, 1.00f,
            0.52f, 1.05f, 0.45f, 0.70f,
            new Vec3(1.00, 0.88, 0.55), new Vec3(1.00, 0.48, 0.08), new Vec3(0.48, 0.07, 0.02));

    /** <b>烈焰 / 喷射</b>：强湍流、拉长、亮黄白核心、更实。 */
    public static final FlameStylePreset JET = register(
            "jet", "烈焰喷射", "强湍流、往外拉得远、亮黄白核心、更实更凶",
            3.0f, 5.5f, 8.5f, 0.72f,
            0.92f, 1.35f,
            0.64f, 0.95f, 0.25f, 0.45f,
            new Vec3(1.00, 1.00, 0.95), new Vec3(1.00, 0.72, 0.22), new Vec3(0.72, 0.20, 0.03));

    /** <b>蓝焰 / 魔法火</b>：青色核心、冷色边缘（甲烷/酒精那种"蓝火"）。 */
    public static final FlameStylePreset BLUE = register(
            "blue", "蓝焰/魔法火", "青色核心、冷蓝边缘、亮而薄（不是柴火那种橙）",
            5.0f, 3.0f, 6.6f, 0.66f,
            0.85f, 1.25f,
            0.46f, 1.45f, 0.32f, 0.40f,
            new Vec3(0.72, 0.92, 1.00), new Vec3(0.24, 0.52, 1.00), new Vec3(0.06, 0.14, 0.52));

    /** <b>余烬</b>：低矮、暗红、闪得慢、整体偏暗（烧到尾声的样子）。 */
    public static final FlameStylePreset EMBER = register(
            "ember", "余烬", "低矮、暗红、闪得慢、整体偏暗（火快灭了的余烬）",
            3.0f, 0.7f, 3.4f, 0.74f,
            0.72f, 0.85f,
            0.30f, 2.20f, 0.30f, 0.60f,
            new Vec3(1.00, 0.62, 0.26), new Vec3(0.72, 0.20, 0.05), new Vec3(0.30, 0.04, 0.01));

    /**
     * <b>旧名别名表</b>：{@code 别名 → 预设}。
     *
     * <p>只为了<b>向后兼容</b>：默认档在 2026-10-29 之前叫 {@code candle}，老玩家、老的宏/脚本、
     * 旧文档里还在敲 {@code /yiz flame preset candle}。这里把 {@code candle} 映到
     * {@link #CLASSIC}，解析到的就是<b>同一个预设对象</b>（参数值一个都没变）。
     * 指令节点由 {@link #aliases()} 注册，{@code preset list} 里也会标出别名关系。</p>
     *
     * <p><b>别名不进 {@link #ALL}</b> —— 所以 {@code preset list} 的条目数、顺序都不受影响
     * （仍然是 classic → torch → campfire → jet → blue → ember 六档）。</p>
     *
     * <p>用 {@link LinkedHashMap} 是为了让别名在指令补全里也有稳定顺序（以后加别名直接 {@code put}）。</p>
     */
    private static final Map<String, FlameStylePreset> ALIASES = new LinkedHashMap<>();

    static {
        // "candle" = 2026-10-29 改名前的旧名 → 现在的 "classic"（经典/Classic）
        ALIASES.put("candle", CLASSIC);
    }

    private static FlameStylePreset register(String id, String label, String blurb,
                                             float noiseScale, float flow, float density, float turbulence,
                                             float opacity, float brightness,
                                             float marchLength, float falloffK, float edgeFadeMin,
                                             float sparkChance, Vec3 core, Vec3 mid, Vec3 edge) {
        FlameStylePreset p = new FlameStylePreset(id, label, blurb, noiseScale, flow, density, turbulence,
                opacity, brightness, marchLength, falloffK, edgeFadeMin, sparkChance, core, mid, edge);
        ALL.put(id, p);
        return p;
    }

    // ────────────────────────── 当前生效的预设 ──────────────────────────

    /**
     * 当前生效的预设（默认 = {@link #CLASSIC} = 回退后的可见档）。
     *
     * <p>{@code volatile}：写来自指令线程（服务端指令在主线程执行，但客户端渲染线程会读），
     * 读在多处、写很少 —— 用 volatile 保证"切换后渲染线程一定能看到新值"。</p>
     */
    private static volatile FlameStylePreset active = CLASSIC;

    // 注意：本类**刻意没有**无参构造 —— 所有字段都是 final，唯一入口是上面的 register(...)，
    // 也就不存在"空预设"这种状态。要加新预设请加一行 register(...)，不要 new。

    /** 当前生效的预设（渲染/生成时逐帧读，不要缓存到 static final）。 */
    public static FlameStylePreset active() {
        return active;
    }

    /** 全部预设（顺序 = 注册顺序）。 */
    public static List<FlameStylePreset> all() {
        return Collections.unmodifiableList(new ArrayList<>(ALL.values()));
    }

    /**
     * 按名字查预设；<b>支持旧名别名</b>（如 {@code candle → classic}）；没有则 {@code null}。
     *
     * <p>别名解析放在这里而不是各调用点，是为了让所有入口（指令、未来的配置/脚本）
     * 都天然兼容，且别名与正式 id 落到<b>同一个对象</b>。</p>
     */
    public static FlameStylePreset byId(String id) {
        if (id == null) return null;
        FlameStylePreset p = ALL.get(id);
        return p != null ? p : ALIASES.get(id);
    }

    /** 全部旧名别名（指令节点注册与 {@code preset list} 标注用，顺序 = 注册顺序）。 */
    public static List<String> aliases() {
        return Collections.unmodifiableList(new ArrayList<>(ALIASES.keySet()));
    }

    /**
     * 这个输入是不是<b>旧名别名</b>（而非正式 id）。
     * 指令回显据此多说一句"candle 是 classic 的旧名，参数一样"。
     */
    public static boolean isAlias(String id) {
        return id != null && !ALL.containsKey(id) && ALIASES.containsKey(id);
    }

    /** 别名 → 正式 id（不是别名则原样返回，便于回显/日志统一）。 */
    public static String canonicalId(String id) {
        FlameStylePreset p = byId(id);
        return p != null ? p.id : id;
    }

    /**
     * 切换当前预设。
     *
     * @return 切换成功返回新预设；名字不存在返回 {@code null}（调用方据此回显错误）
     */
    public static FlameStylePreset select(String id) {
        FlameStylePreset p = byId(id);
        if (p == null) return null;
        active = p;
        return p;
    }

    /** 日志/回显用：一行关键数值。 */
    public static String describe() {
        FlameStylePreset p = active;
        return String.format(Locale.ROOT,
                "%s(%s) 噪声=%.1f 流速=%.1f 密度=%.1f 湍流=%.2f 透明度=%.2f 亮度=%.2f 光程=%.2f 衰减=%.2f 火星=%.0f%%",
                p.id, p.label, p.noiseScale, p.flow, p.density, p.turbulence,
                p.opacity, p.brightness, p.marchLength, p.falloffK, p.sparkChance * 100.0f);
    }
}
