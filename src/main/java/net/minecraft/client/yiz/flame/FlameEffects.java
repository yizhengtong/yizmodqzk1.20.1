package net.minecraft.client.yiz.flame;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 体积火焰效果管理器（客户端侧状态 + 公共 API）。
 *
 * <p>四种形态共用同一个着色器程序（{@code rendertype_flame_volume}），差别只在"形状遮罩"：</p>
 * <ul>
 *   <li>{@link Shape#WALL} 火墙：沿 {@code axis} 的竖直长条，参数 = 跨度 / 高度 / 壳厚；</li>
 *   <li>{@link Shape#RING} 火环：水平圆壳（圆柱面），参数 = 半径 / 高度；</li>
 *   <li>{@link Shape#ROPE} 火绳：沿折线控制点的管状体积（最多 8 点），参数 = 管半径；</li>
 *   <li>{@link Shape#BURNING} 着火：绕实体的包络（自动跟随实体位置），参数 = 半径 / 高度。</li>
 * </ul>
 *
 * <p>效果本身只是"体积参数 + 寿命"，真正出画面的是 {@code FlameRenderer} 里的射线步进着色器；
 * 本类不做任何 GL 操作，服务端/客户端都能安全调用 <b>spawn</b>（只在客户端生效）。</p>
 */
public final class FlameEffects {

    /** 临时诊断日志（定位"贴模型火完全看不见"用，见 {@code MirrorDiag}）。 */
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("YizFlame");

    private FlameEffects() {}

    /** 诊断日志里的两位小数（Locale.ROOT：避免某些区域用逗号当小数点，日志反而不好读）。 */
    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }

    public enum Shape { WALL, RING, ROPE, BURNING }

    /**
     * 锚定方式（对应 shader 的 uNoiseAnchor / uAnchorYaw）。
     *
     * <ul>
     *   <li>{@link #WORLD} <b>客观世界坐标固定</b>：位置与噪声都钉死在世界里。
     *       同一处火墙反复生成，花纹永远一致；相机怎么动、实体怎么走都不影响。</li>
     *   <li>{@link #ENTITY} <b>追踪实体实时位置</b>：每帧取实体的插值位置作为锚点，
     *       噪声采样转到实体局部坐标；{@link Flame#followYaw} 打开时几何轴向与花纹还会跟着实体转身。
     *       适合"身上着火""绕着自己转的火环""随武器甩动的火绳"。</li>
     * </ul>
     */
    public enum Anchor { WORLD, ENTITY }

    /** 默认火焰三段色：核心白热 → 中层橙黄 → 外焰暗红。 */
    public static final Vec3 CORE = new Vec3(1.00, 0.95, 0.75);
    public static final Vec3 MID  = new Vec3(1.00, 0.55, 0.12);
    public static final Vec3 EDGE = new Vec3(0.55, 0.09, 0.02);

    /**
     * 体积侧湍流阈值相对贴模型火的换算比（{@code 0.30 / 0.62 ≈ 0.484}）。
     *
     * <p>为什么两条路径不能直接用同一个阈值：贴模型火是 0.42 格的短光程、密度 5.0；
     * 体积火是绕实体的小火团（密度另有 0.65 合成补偿与 0.55 透明度缩放），
     * 同一个阈值搬到体积侧会几乎全空。这个比值就是两条路径长期实测下来的换算，
     * 默认档（{@code 0.62 × 0.484 ≈ 0.30}）与改动前的体积火逐值相同。</p>
     */
    private static final float PATCH_TURBULENCE_RATIO = 0.30f / 0.62f;

    /**
     * 火花相对主火焰的"更碎更快"倍数：默认档 {@code 4.4 × 1.364 = 6.0}、{@code 2.2 × 1.364 = 3.0}
     * —— 正是改动前写死的两个数，于是默认档下火花与改动前逐值相同，换预设时又会跟着变。
     */
    private static final float SPARK_DETAIL_SCALE = 6.0f / 4.4f;

    /**
     * 预设密度的基准值（默认档密度）。
     *
     * <p>原引自已归档的烛火特效（2026-10-29 归档到 {@code 废案\烛火-candle-fire-2026-10-29}，密度 5.0）。
     * 归档后写死为等价常量：
     * 体积补丁密度 = {@code patchDensity × (当前预设密度 / 本值)}，默认档下逐值不变。</p>
     */
    public static final float DEFAULT_STYLE_DENSITY = 5.0f;

    /** 一个火焰体积实例（公开字段方便调用方微调；改完立即生效，无需重建）。 */
    public static final class Flame {
        public final Shape shape;

        /** 世界坐标原点（火墙/火环的中心、着火的实体位置）。跟随实体时每帧自动刷新。 */
        public Vec3 origin;
        /** 火墙的走向（水平方向；水平分量会被归一化）。 */
        public Vec3 axis = new Vec3(0.0, 0.0, 1.0);

        /** 壳体厚度 / 半径：火墙=法向厚度，火环=圆环半径，火绳=管半径，着火=包络半径。 */
        public float thickness = 1.0f;
        /** 垂直尺度：火焰向上衰减到 0 的高度。 */
        public float height = 3.0f;
        /** 水平跨度：火墙长度 / 火环用不到（取 thickness）/ 火绳用不到。 */
        public float span = 6.0f;

        // ── 噪声与体积参数（对应 shader 的 uParams / uParams2）──
        /** 噪声尺度：越大火焰舌越碎越细。 */
        public float noiseScale = 1.6f;
        /** 上升流动速度（噪声 y 方向偏移速率）。 */
        public float flow = 1.1f;
        /** 密度 / 发光强度：越大越不透明越亮。 */
        public float density = 6.0f;
        /** 湍流阈值：越大只有越"浓"的湍流区有火焰 → 舌状更明显、更空灵。 */
        public float turbulence = 0.42f;
        /** 整体不透明度上限（0~1）。 */
        public float alpha = 1.0f;
        /**
         * <b>火焰自身亮度</b>（乘在最终累积颜色上，1.0 = 原样）。
         *
         * <p>为什么需要它：体积火的输出是<b>预乘 α</b>（{@code acc * centerMask}），alpha 只决定
         * "透出多少本体"，光的强度全在 {@code acc} 里；而体积火在暗贴图的生物身上会被底色读成"很深的暗火"。
         * 亮度与"透明度"分开之后，就能<b>只提亮、不变实</b>。</p>
         *
         * <p>取值：由"着火"族的生成处从当前预设写入（默认档 = 1.0，与改动前逐字节相同）；
         * 其余形态（火墙/火环/火绳/火壳）保持这里的默认 1.0。</p>
         *
         * <p>这是<b>通用提亮能力</b>：只影响片元颜色的强度，不改变透明度，也不再专属于某一种特效。</p>
         *
         * <p><b>踩过的坑</b>：这个 uniform 必须同时在
         * {@code rendertype_flame_volume.json} 的 {@code "uniforms"} 里声明 ——
         * 只写在 GLSL 里的话 Java 侧 {@code safeGetUniform} 静默失效、GLSL 停在 0，
         * 输出就变成"黑颜色 + 非零 alpha"（预乘混合下等于把本体压暗，整片特效像脏影）。
         * 着色器里另有一道 {@code <=0 → 1.0} 的防呆。</p>
         */
        public float brightness = 1.0f;
        /** 整体染色（乘在火焰颜色上）。 */
        public float tintR = 1.0f, tintG = 1.0f, tintB = 1.0f;

        public Vec3 colorCore = CORE, colorMid = MID, colorEdge = EDGE;

        /** 火绳控制点（世界坐标，最多 8 个）。 */
        public Vec3[] points;

        /** 锚定方式：世界固定 / 追踪实体实时位置。 */
        public Anchor anchor = Anchor.WORLD;
        /** 锚定目标实体（{@link Anchor#ENTITY} 时有效）。 */
        public Entity follower;
        /** 相对实体的垂直偏移（一般取实体高度的一半，让火焰包住身体）。 */
        public double followYOffset = 0.0;
        /**
         * 相对实体的<b>水平局部偏移</b>（XZ，随实体偏航一起旋转）。
         * 着火蔓延的每一团小火焰都靠它"贴"在身体的不同位置上；世界锚定时忽略。
         */
        public Vec3 followOffset = Vec3.ZERO;
        /** 是否让几何轴向与噪声花纹跟着实体转身（关掉则只跟位置、不跟朝向）。 */
        public boolean followYaw = true;
        /** 挂载瞬间记录的实体位置/偏航 —— 用来把点集转成"实体相对"，并算出偏航增量。 */
        public Vec3 followSpawnPos;
        public float followSpawnYaw;

        /**
         * 把效果挂到实体上（<b>追踪实体实时变动位置</b>）：位置、朝向、噪声锚点全部实时跟随。
         *
         * <p>与"在世界坐标里固定"相对；挂载后 {@link #origin} 不再被使用（以实体位置为准）。
         * 火绳的点集同理按挂载瞬间的实体位置转成实体相对，之后随实体平移与转身一起走。</p>
         *
         * @param yOffset  相对实体的垂直偏移（如实体高度的一半）
         * @param yawFollow 是否跟随实体朝向（转身时火焰轴向与花纹一起转）
         */
        public Flame follow(Entity entity, double yOffset, boolean yawFollow) {
            if (entity == null) return this;
            this.anchor = Anchor.ENTITY;
            this.follower = entity;
            this.followYOffset = yOffset;
            this.followYaw = yawFollow;
            this.followSpawnPos = entity.position();
            this.followSpawnYaw = entity.getYRot();
            return this;
        }

        /** 切回世界固定（保留当前实体位置作为新原点）。 */
        public Flame unfollow() {
            if (this.follower != null) this.origin = this.follower.position();
            this.anchor = Anchor.WORLD;
            this.follower = null;
            return this;
        }

        public int life;
        public final int maxLife;
        /** 淡入/淡出 tick 数（避免"啪"地出现和消失）。 */
        public int fadeIn = 4;
        public int fadeOut = 8;
        /** 累计 tick（用于淡入淡出计算）。 */
        public int age;
        /** 是否已打印过首帧绘制诊断日志（一次性，避免每帧刷屏）。 */
        public boolean renderLogged;
        /**
         * 短命特效（着火蔓延里"偶尔冒出的火花"）：不参与长大、不被续命，
         * 活完自己的寿命就淡出消失 —— 于是它们是"一闪而过"而不是越烧越旺。
         */
        public boolean ephemeral;

        Flame(Shape shape, Vec3 origin, int life) {
            this.shape = shape;
            this.origin = origin;
            this.life = life;
            this.maxLife = Math.max(1, life);
        }
    }

    private static final List<Flame> ACTIVE = new ArrayList<>();
    /** 正在蔓延的着火簇（它们自己管理名下的补丁 Flame）。 */
    private static final List<BurningSpread> SPREADS = new ArrayList<>();
    /** 正在"贴模型着火"的状态（见 {@link #burningSurface}，渲染 hook 每帧按实体查询）。 */
    private static final List<SurfaceBurning> SURFACE = new ArrayList<>();

    /** 当前活动效果（只读；渲染器直接遍历）。 */
    public static List<Flame> active() {
        return Collections.unmodifiableList(ACTIVE);
    }

    // ────────────────────────── 工厂 ──────────────────────────

    /** 火墙：origin 为墙底中心，axis 为走向（水平），跨度 span 沿 axis、高度 height 向上、厚度 thickness 沿法向。 */
    public static Flame wall(Vec3 origin, Vec3 axis, float span, float height, float thickness, int life) {
        Flame f = new Flame(Shape.WALL, origin, life);
        f.axis = axis;
        f.span = span;
        f.height = height;
        f.thickness = thickness;
        f.noiseScale = 1.9f;
        f.flow = 1.35f;
        f.density = 6.5f;
        f.turbulence = 0.40f;
        ACTIVE.add(f);
        return f;
    }

    /** 火环：origin 为环底中心，radius 为环半径，height 为火墙高度。 */
    public static Flame ring(Vec3 origin, float radius, float height, int life) {
        Flame f = new Flame(Shape.RING, origin, life);
        f.thickness = 0.9f;
        f.span = radius;        // 环半径走 span（见 shader shapeMask 的 s==1 分支）
        f.height = height;
        f.noiseScale = 2.2f;
        f.flow = 1.5f;
        f.density = 7.0f;
        f.turbulence = 0.38f;
        ACTIVE.add(f);
        return f;
    }

    /** 火绳：沿 points（世界坐标，2~8 点）的管状火焰，thickness 为管半径。 */
    public static Flame rope(List<Vec3> points, float thickness, int life) {
        Flame f = new Flame(Shape.ROPE, points.isEmpty() ? Vec3.ZERO : points.get(0), life);
        int n = Math.min(points.size(), 8);
        f.points = new Vec3[8];
        for (int i = 0; i < 8; i++) {
            f.points[i] = points.get(Math.min(i, n - 1));
        }
        f.thickness = thickness;
        f.noiseScale = 3.0f;
        f.flow = 1.8f;
        f.density = 8.0f;
        f.turbulence = 0.34f;
        ACTIVE.add(f);
        return f;
    }

    /**
     * 着火（3D 蔓延）：在目标身上陆续冒出一簇<b>小团火焰</b>，随时间<b>变多、变大</b>，
     * 像火顺着身体爬开 —— 而不是套一层均匀的火壳。
     *
     * <p>每团补丁是一个独立的小体积火焰（复用同一着色器），贴在<b>实体局部坐标</b>上：
     * 目标走动它跟着走、目标转身它跟着转。新补丁的位置从已有补丁旁边"就近冒出"并带向上偏置，
     * 于是蔓延有方向感（从着火点往上爬），而不是全身同时亮。</p>
     *
     * @param target         被点燃的实体
     * @param maxPatches     最多几团（性能上限，一般 6~14）
     * @param spreadInterval 每多少 tick 冒出新的一团（越小蔓延越快）
     * @param life           整体寿命（tick）；到期后所有补丁一起淡出。{@code <=0} = 不自动结束
     */
    public static BurningSpread burning(Entity target, int maxPatches, int spreadInterval, int life) {
        BurningSpread spread = new BurningSpread(target, maxPatches, spreadInterval, life);
        SPREADS.add(spread);
        return spread;
    }

    /**
     * 贴模型着火（"着火"的正式形态）：火焰画在<b>实体自己的模型几何</b>上，而不是套一个碰撞箱大小的火盒。
     *
     * <p>几何来源：实体渲染时 {@code ModelMirrorBufferSource} 把模型顶点多抄一份，
     * 再用 {@code rendertype_flame_surface} 从模型表面沿法线向外短程步进积分 ——
     * 于是火是顺着头/躯干/四肢舔出来的，关节动、火跟着动（GeckoLib 之类自定义模型同理）。
     * 手持物/掉落物/盔甲由 {@code MirrorExclusion} 的渲染期标志排除，不参与灼烧。</p>
     *
     * <p>本类只管状态：{@code spread = age / spreadTicks}（0→1）驱动"从下往上爬满模型"，
     * 寿命到期 / 实体死亡则淡出。渲染入口见 {@code FlameSurfaceLayer}（AFTER_ENTITIES 由
     * {@code ModelMirrorRenderer} 驱动）。</p>
     *
     * @param target      被点燃的实体
     * @param spreadTicks 从起燃点爬满全身所需的 tick（越小蔓延越快）
     * @param life        整体寿命（tick）；{@code <=0} = 不自动结束
     */
    public static SurfaceBurning burningSurface(Entity target, int spreadTicks, int life) {
        SurfaceBurning s = new SurfaceBurning(target, spreadTicks, life);
        SURFACE.add(s);
        // 诊断（降到 debug：这条以前每建一个状态就打一条 info，实测时把关键日志淹了）：
        // 打出<b>目标自己的身高</b>与本次蔓延/寿命，用来对账"火线是否按各自身高归一化"。
        LOGGER.debug("[FlameSurface] 状态已创建: entity={} 身高={} spreadTicks={} life={} 当前贴模型着火实体数={}",
                net.minecraft.client.yiz.modelmirror.MirrorDiag.entity(target),
                fmt(target.getBbHeight()), spreadTicks, life, SURFACE.size());
        return s;
    }

    /**
     * 查询某实体的贴模型着火状态（渲染 hook 每帧查一次，代价 = 线性扫描，同时着火的实体只有个位数）。
     *
     * @return 该实体的着火状态；没有则 {@code null}
     */
    public static SurfaceBurning surfaceBurningOf(Entity entity) {
        for (int i = 0; i < SURFACE.size(); i++) {
            SurfaceBurning s = SURFACE.get(i);
            if (s.target == entity) return s;   // 同一实体对象（引用比较，避免 equals 带来的意外匹配）
        }
        return null;
    }

    /** 临时诊断用：当前有几条贴模型着火状态（渲染器据此区分"状态没建"与"实体没进渲染"）。 */
    public static int surfaceStateCount() {
        return SURFACE.size();
    }

    /**
     * 火壳：绕实体的一圈均匀火焰（旧版"着火"观感）。
     * 适合"整只生物都在烧"的简化表现；要"小团火焰蔓延"请用 {@link #burning}。
     */
    public static Flame fireShell(Entity entity, float radius, float height, int life) {
        Flame f = new Flame(Shape.BURNING, entity.position(), life);
        f.thickness = radius;
        f.height = height;
        f.noiseScale = 3.4f;
        f.flow = 2.0f;
        f.density = 7.5f;
        f.turbulence = 0.30f;
        f.fadeIn = 2;
        f.fadeOut = 6;
        f.follow(entity, entity.getBbHeight() * 0.5, true);
        ACTIVE.add(f);
        return f;
    }

    /**
     * 着火蔓延簇：管理一簇贴在实体身上的小火焰补丁。
     *
     * <p>生命周期：每隔 {@code spreadInterval} tick 冒一团新的（位置从已有补丁旁就近取 + 向上偏置），
     * 同时每团按 {@code grow} 速率长大到上限；到 {@code maxPatches} 后维持（靠噪声自己闪烁）。
     * 到期或目标死亡 → 所有补丁淡出并移除。</p>
     */
    public static final class BurningSpread {

        /** 单团补丁的尺寸上下限（格）。刻意做得小：火焰是"贴着身上烧"，不是一个罩住模型的火笼。 */
        private static final float MIN_RADIUS = 0.13f, MAX_RADIUS = 0.30f;
        private static final float MIN_HEIGHT = 0.32f, MAX_HEIGHT = 0.75f;
        /** 长大速率相对值（按 tick 线性插值到上限）。 */
        private static final int GROW_TICKS = 34;
        /** 火花（短命小团）的尺寸与寿命。 */
        private static final float SPARK_RADIUS = 0.10f, SPARK_HEIGHT = 0.24f;
        private static final int SPARK_LIFE = 12;
        /**
         * 火星概率（老入口的值，改动前一字未改）。实际概率 = 当前预设的 {@code sparkChance}
         * 按 {@link #SPARK_CHANCE_SCALE} 缩放到"体积补丁这一侧"（贴模型火的默认概率是 0.35，见
         * {@link SurfaceBurning}），于是预设调火星只调一个数、两条路径同向变化。
         */
        private static final float SPARK_CHANCE = 0.28f;
        /** 体积补丁的火星概率相对"贴模型火默认概率"的缩放：0.28/0.35 = 0.8（默认档逐值还原）。 */
        private static final float SPARK_CHANCE_SCALE = SPARK_CHANCE / 0.35f;
        /**
         * 自己着火 + 第一人称时的冻结时刻（tick，40 ≈ 2.0 秒）。
         * 第一人称下火焰铺满视野，再继续蔓延只会越叠越亮、把本来模糊的边界顶成刺眼的硬边；
         * 到点冻结（不再新增、不再长大），保留当前形态即可。
         */
        private static final int LOCAL_FIRST_PERSON_FREEZE = 40;

        /**
         * 老入口（{@link FlameEffects#burning}）的补丁中心高度上限（占身高比例）。
         * <b>0.48 = 改动前的原值</b>（火团集中在腰以下），老入口靠它逐值还原。
         */
        private static final float DEFAULT_MAX_CENTER_RATIO = 0.48f;
        /** 老入口的补丁密度（体积火的等效密度）。<b>6.5 = 改动前的原值</b>。 */
        private static final float DEFAULT_PATCH_DENSITY = 6.5f;
        /** 起燃点高度（占身高比例）：<b>0.22 = 改动前的原值</b>（身体中下部）。 */
        private static final float START_Y_RATIO = 0.22f;
        /** 蔓延时的向上步长（占身高比例区间）：{@code 0.05 + rand*0.12} = 改动前的原值。 */
        private static final double UP_STEP_MIN = 0.05, UP_STEP_RAND = 0.12;
        /** 补丁中心允许的最低高度（占身高比例）：{@code 0.04 = 改动前的原值}（脚踝附近）。 */
        private static final float MIN_CENTER_RATIO = 0.04f;
        /**
         * 体积补丁的湍流阈值 = 贴模型火的阈值 × 本系数（换算比见 {@link FlameEffects#PATCH_TURBULENCE_RATIO}）。
         */
        private static final float PATCH_TURBULENCE_RATIO = FlameEffects.PATCH_TURBULENCE_RATIO;
        /** 火花相对主火焰的"更碎更快"倍数（定义见 {@link FlameEffects#SPARK_DETAIL_SCALE}）。 */
        private static final float SPARK_DETAIL_SCALE = FlameEffects.SPARK_DETAIL_SCALE;

        private final Entity target;
        private final List<Flame> patches = new ArrayList<>();
        private final int maxPatches;
        private final int spreadInterval;
        private final int life;
        private final net.minecraft.util.RandomSource random = net.minecraft.util.RandomSource.create();
        private final float halfWidth;
        private final float bodyHeight;
        /** 补丁中心的允许高度区间（占身高比例）：固定 0.04~0.48 身高（改动前的原值）。 */
        private final double minYRatio, maxYRatio;
        /** 主火焰密度（体积火的等效密度；贴模型火不吃这个值，它有自己的 uniform）。 */
        private final float patchDensity;

        private int age;
        private boolean ended;
        private boolean frozen;

        /** 逐值与改动前相同（中心 ≤ 0.48 身高、单团火最高 {@link #MAX_HEIGHT} 格、密度 6.5）。 */
        BurningSpread(Entity target, int maxPatches, int spreadInterval, int life) {
            this.target = target;
            this.maxPatches = Math.max(1, Math.min(24, maxPatches));
            this.spreadInterval = Math.max(1, spreadInterval);
            this.life = life;
            this.halfWidth = (float) Math.max(target.getBbWidth() * 0.5, 0.25);
            this.bodyHeight = (float) Math.max(target.getBbHeight(), 0.6);
            this.patchDensity = DEFAULT_PATCH_DENSITY;
            this.maxYRatio = Math.max(0.05f, DEFAULT_MAX_CENTER_RATIO);
            this.minYRatio = Math.min(MIN_CENTER_RATIO, this.maxYRatio * 0.5);
            spawnPatch(null);   // 第一团：起燃点（身体中下部）
        }

        public List<Flame> patches() {
            return Collections.unmodifiableList(patches);
        }

        public int age() {
            return age;
        }

        boolean finished() {
            return ended && patches.isEmpty();
        }

        void tick() {
            if (!target.isAlive()) {
                stop();
            } else {
                age++;
                if (life > 0 && age >= life) stop();
                //  自己着火 + 第一人称：到 ~1.4 秒就冻结（不再新增/长大/变亮）
                if (!frozen && !ended && age >= LOCAL_FIRST_PERSON_FREEZE && isFirstPersonSelf()) freeze();
            }
            if (!ended && !frozen && patches.size() < maxPatches && age % spreadInterval == 0) {
                spawnPatch(patches.size() == 0 ? null : patches.get(random.nextInt(patches.size())));
            }
            // 每团自己长大（到上限为止）；火花不长大、也不续命（活完自己的寿命就消失）
            float radiusStep = (MAX_RADIUS - MIN_RADIUS) / GROW_TICKS;
            float heightStep = (MAX_HEIGHT - MIN_HEIGHT) / GROW_TICKS;
            for (Flame p : patches) {
                if (!frozen && !p.ephemeral) {
                    p.thickness = Math.min(MAX_RADIUS, p.thickness + radiusStep);
                    p.height = Math.min(MAX_HEIGHT, p.height + heightStep);
                }
                // 续命：只要还在蔓延就一直存活（fade() 的淡出项恒为 1），结束后不再续 → 自然淡出
                if (!ended) p.life = Math.max(p.life, 64);
            }
        }

        /** 是否"自己着火 + 第一人称"（只有这种情况才需要冻结蔓延）。 */
        private boolean isFirstPersonSelf() {
            var mc = net.minecraft.client.Minecraft.getInstance();
            return mc.player == target && mc.options.getCameraType().isFirstPerson();
        }

        /** 冻结蔓延：保留当前形态，不再新增补丁、不再长大（第一人称自己着火用）。 */
        public void freeze() {
            frozen = true;
        }

        public boolean isFrozen() {
            return frozen;
        }

        /** 结束蔓延：不再续命，让已存在的补丁按各自 fadeOut 淡出。 */
        public void stop() {
            if (ended) return;
            ended = true;
            for (Flame p : patches) {
                p.life = Math.min(p.life, Math.max(1, p.fadeOut));
                p.fadeIn = 0;
            }
        }

        /**
         * 冒出一团新补丁。
         *
         * @param near 参考补丁（就近蔓延）；{@code null} = 起燃点（身体中下部）
         */
        private void spawnPatch(Flame near) {
            // 当前预设：粗细/快慢/浓淡/明暗/颜色/火星概率都从它读（默认档见 FlameStylePreset.CLASSIC）。
            FlameStylePreset style = FlameStylePreset.active();
            // 火星概率（老入口默认 0.28，营火预设会更高）：更小、更亮、短命，且不占主火焰的名额（只进 ACTIVE）
            boolean spark = near != null
                    && random.nextFloat() < style.sparkChance * SPARK_CHANCE_SCALE;

            double localX;
            double localZ;
            double y;
            if (near == null) {
                //  起燃点：贴着身体圆柱面（半径约 0.35~0.65 倍半宽），高度偏下 → 从下往上烧。
                //  0.22 身高 = <b>改动前的原值</b>（与老入口逐值相同）。
                double ang = random.nextDouble() * Math.PI * 2.0;
                double r = halfWidth * (0.35 + random.nextDouble() * 0.30);
                localX = Math.cos(ang) * r;
                localZ = Math.sin(ang) * r;
                y = clamp(bodyHeight * START_Y_RATIO, minYRatio * bodyHeight, maxYRatio * bodyHeight);
            } else {
                //  就近 + 绕身 + 向上：沿极角推进一小段、再往上爬一点（绕身蔓延，不是方盒里乱堆）
                double baseAng = Math.atan2(near.followOffset.z, near.followOffset.x);
                double dAng = (random.nextDouble() - 0.5) * 1.5;
                //  主火焰贴着身体表面；火花往外飘一点，离开身体
                double r = halfWidth * (spark
                        ? 0.8 + random.nextDouble() * 0.5
                        : 0.35 + random.nextDouble() * 0.35);
                localX = Math.cos(baseAng + dAng) * r;
                localZ = Math.sin(baseAng + dAng) * r;
                //  向上步长 0.05 + rand*0.12 身高 = <b>改动前的原值</b>（火团集中在腰以下的节奏）；
                //  夹取上下限：上限已经扣掉单团火自身高度，所以越线的是"火苗尾巴"而不是中心。
                y = clamp(near.followYOffset + bodyHeight * (UP_STEP_MIN + random.nextDouble() * UP_STEP_RAND),
                          minYRatio * bodyHeight, maxYRatio * bodyHeight);
            }

            Flame p = new Flame(Shape.BURNING, target.position(), spark ? SPARK_LIFE : 64);
            // 体积补丁的湍流阈值 = 贴模型火阈值 × 换算比（见 PATCH_TURBULENCE_RATIO），默认档 = 改动前的 0.30
            p.turbulence = Math.min(0.9f, style.turbulence * PATCH_TURBULENCE_RATIO) + random.nextFloat() * 0.06f;
            p.follow(target, y, true);
            p.followOffset = new Vec3(localX, 0.0, localZ);

            if (spark) {
                p.ephemeral = true;
                p.thickness = SPARK_RADIUS * (0.8f + random.nextFloat() * 0.5f);
                p.height = SPARK_HEIGHT * (0.8f + random.nextFloat() * 0.5f);
                p.noiseScale = style.noiseScale * SPARK_DETAIL_SCALE;   // 火花更碎（默认档 = 改动前的 6.0）
                p.flow = style.flow * SPARK_DETAIL_SCALE;               // 火花窜得更快（默认档 = 改动前的 3.0）
                p.density = 11.0f;      // 小而亮 → 像飞溅的火星
                p.alpha = 0.9f;
                p.colorCore = style.core;
                p.colorMid = style.mid;
                p.colorEdge = style.edge;
                p.fadeIn = 1;
                p.fadeOut = 5;
                ACTIVE.add(p);          // 只进 ACTIVE：不占主火焰名额、也不作为蔓延起点
                return;
            }

            p.thickness = MIN_RADIUS * (0.85f + random.nextFloat() * 0.3f);
            // 出生高度不超过 MAX_HEIGHT（它随后会按 heightStep 长到 MAX_HEIGHT 为止）
            p.height = Math.min(MAX_HEIGHT, MIN_HEIGHT * (0.85f + random.nextFloat() * 0.3f));
            p.noiseScale = style.noiseScale + random.nextFloat() * 0.8f;   // 小团火 → 细节更碎
            p.flow = style.flow + random.nextFloat() * 0.5f;               // 小火苗抖得快
            // 密度随预设缩放：默认档 = patchDensity（6.5，改动前的值）
            p.density = patchDensity * (style.density / DEFAULT_STYLE_DENSITY);
            p.alpha = style.opacity;
            // 与贴模型火同一个亮度（两条路径的"深/淡"因此由同一个来源控制）
            p.brightness = style.brightness;
            p.colorCore = style.core;
            p.colorMid = style.mid;
            p.colorEdge = style.edge;
            p.fadeIn = 3 + random.nextInt(3);
            p.fadeOut = 6;
            ACTIVE.add(p);
            patches.add(p);
        }

        private static double clamp(double v, double lo, double hi) {
            return v < lo ? lo : (v > hi ? hi : v);
        }
    }

    /**
     * 贴模型着火状态（见 {@link #burningSurface}）—— 只管"烧到哪了 / 多亮 / 什么时候停"，
     * 几何与积分全在 {@code rendertype_flame_surface} 里做。
     *
     * <p><b>uSpread 怎么驱动</b>：{@code spread = age / spreadTicks} 线性 0→1，作为片元着色器里
     * "火线"在模型上的归一化高度前沿；着色器用"模型局部高度 + 列噪声扰动"和它比大小，
     * 于是蔓延边缘是不规则的锯齿而不是一条水位线。</p>
     *
     * <p><b>第一人称的三种自动处理</b>（用户明确要求"自己着火 + 第一人称 2.0 秒后停止蔓延/变亮"）：</p>
     * <ol>
     *   <li>{@link #LOCAL_FIRST_PERSON_FREEZE} tick 时把 uSpread 上限钉住（与 {@link BurningSpread}
     *       同一条约定：第一人称火焰铺满视野，再长只会越叠越亮、把边界顶成刺眼硬边）；</li>
     *   <li>原版第一人称<b>不渲染本地玩家模型</b>（{@code LevelRenderer} 的实体循环会跳过摄像机实体），
     *       贴模型火没有几何可画 → 自动挂一套既有的 {@link BurningSpread} 体积火兜底，
     *       保证第一人称仍然"四周一圈火"（它自带 2.0s 冻结、中心剔除与火花）。
     *       <b>用哪一套参数</b>：兜底走 {@link FlameEffects#burning} 的既有观感
     *       （火团可到腰以上，与改动前逐值相同）；</li>
     *   <li>切回第三人称：兜底体积火停掉（避免"模型火 + 碰撞箱火"双重叠加）、蔓延上限放开
     *       （第一人称的视野/帧率约束不存在了，让火焰继续爬满，否则看起来像卡住）。</li>
     * </ol>
     *
     * <p><b>火线高度按"目标自己的身高"归一化</b>：{@code FlameSurfaceLayer} 每帧把
     * {@code entity.getBbHeight()} 传给着色器的 {@code uSpreadHeight}，<b>不写死 1.8</b> ——
     * 于是 2.9 格高的监守者与 1.8 格高的玩家的"火线停在身高某个比例"是同一条规则。</p>
     */
    public static final class SurfaceBurning {

        /**
         * 自己着火 + 第一人称时的冻结时刻（tick，40 ≈ 2.0 秒）。
         * 与 {@link BurningSpread#LOCAL_FIRST_PERSON_FREEZE} 取同一个值，保证"两种形态观感一致"。
         */
        private static final int LOCAL_FIRST_PERSON_FREEZE = 40;
        /** 火花（短命小火团）：尺寸/寿命/密度照抄 {@link BurningSpread} 的 spark 分支，观感一致。 */
        private static final float SPARK_RADIUS = 0.10f, SPARK_HEIGHT = 0.24f;
        private static final int SPARK_LIFE = 12;
        /** 追击间隔与概率：约"每 20 tick 有 1/3 概率冒一朵" → 偶尔一两朵，而不是持续喷火星。 */
        private static final int SPARK_INTERVAL = 20;
        private static final float SPARK_CHANCE = 0.35f;
        /**
         * 火星高度带：{@code 0.45 + rand*0.5} 身高 —— <b>改动前的原值</b>，火星溅在身体中上部。
         */
        private static final double SPARK_BAND_FREE_MIN = 0.45, SPARK_BAND_FREE_RAND = 0.50;
        /** 淡出 tick（{@link #stop()} 后按它收尾）。 */
        private static final int FADE_OUT = 8;

        private final Entity target;
        private final int spreadTicks;
        private final net.minecraft.util.RandomSource random = net.minecraft.util.RandomSource.create();
        private final float halfWidth;
        private final float bodyHeight;

        private int age;
        /** 剩余寿命（tick）；{@code life<=0} 时给一个极大值 = 不自动结束。 */
        private int remaining;
        private boolean ended;
        private boolean frozen;
        private float frozenSpread;
        private int sparkTimer = SPARK_INTERVAL;
        /** 第一人称自己着火时的体积火兜底（见类注释第 2 条）。 */
        private BurningSpread firstPersonFallback;

        SurfaceBurning(Entity target, int spreadTicks, int life) {
            this.target = target;
            this.spreadTicks = Math.max(1, spreadTicks);
            this.remaining = life > 0 ? life : Integer.MAX_VALUE;
            this.halfWidth = (float) Math.max(target.getBbWidth() * 0.5, 0.25);
            this.bodyHeight = (float) Math.max(target.getBbHeight(), 0.6);
        }

        /** 蔓延进度 0→1（冻结后钉在冻结时的值，切回第三人称会放开继续增长）。 */
        public float spread() {
            if (frozen) return frozenSpread;
            return Math.min(1.0f, age / (float) spreadTicks);
        }

        /** 整体透明度（淡入 4 tick / 淡出 {@link #FADE_OUT} tick）：给顶点色 alpha 用。 */
        public float alpha() {
            float in = Math.min(1.0f, age / 4.0f);
            float out = Math.min(1.0f, remaining / (float) FADE_OUT);
            return Math.max(0.0f, Math.min(in, out));
        }

        /**
         * 火焰自身亮度（{@code FlameSurfaceLayer} 喂给 {@code uBrightness}）。
         *
         * <p>取值来自<b>当前预设</b>（{@link FlameStylePreset#active()}），与第一人称体积兜底、
         * 以及体积补丁（它们各自把同一个值写进 {@code Flame#brightness}）是<b>同一个来源</b> ——
         * 这就是"玩家/生物观感一致"的代码级保证（不是靠两边各调一遍数字凑出来的）。</p>
         *
         * <p>注意它只是"顺手提供"的读取口：真正喂给贴模型着色器的是
         * {@code FlameSurfaceLayer.setUniforms} 里的同一表达式（那边逐帧读，切预设立刻生效）。</p>
         */
        public float brightness() {
            return FlameStylePreset.active().brightness;
        }

        /** 冻结蔓延：把 uSpread 上限钉在当前值（第一人称视野/帧率保护）。 */
        public void freeze() {
            if (frozen) return;
            frozenSpread = Math.min(1.0f, age / (float) spreadTicks);
            frozen = true;
        }

        /** 结束蔓延：进入淡出（{@link #alpha()} 随之衰减），到期由 {@link FlameEffects#tick()} 回收。 */
        public void stop() {
            if (ended) return;
            ended = true;
            remaining = Math.min(remaining, FADE_OUT);
            // 兜底体积火一起收尾，避免主状态被回收后它还留在 SPREADS 里自己烧
            if (firstPersonFallback != null) {
                firstPersonFallback.stop();
                firstPersonFallback = null;
            }
        }

        boolean finished() {
            return ended && remaining <= 0;
        }

        void tick() {
            if (!target.isAlive() || target.isRemoved()) {
                stop();
            } else {
                age++;
                remaining--;
                boolean firstPersonSelf = isFirstPersonSelf();
                if (firstPersonSelf) {
                    // 自己着火 + 第一人称：2.0 秒后不再蔓延/变亮
                    if (!ended && age >= LOCAL_FIRST_PERSON_FREEZE) freeze();
                } else if (frozen) {
                    // 切回第三人称：放开上限，让火焰继续爬满模型（否则看起来像"火卡住了"）
                    frozen = false;
                }
                syncFirstPersonFallback(firstPersonSelf);
                if (!ended && !frozen && firstPersonFallback == null) spawnSparkIfDue();
            }
        }

        /** 是否"自己着火 + 第一人称"（与 {@link BurningSpread} 同一判据）。 */
        private boolean isFirstPersonSelf() {
            var mc = net.minecraft.client.Minecraft.getInstance();
            return mc.player == target && mc.options.getCameraType().isFirstPerson();
        }

        /**
         * 第一人称兜底体积火的自动收放（见类注释第 2/3 条）。
         * 兜底存在期间不再生成自己的火花 —— {@link BurningSpread} 本来就带火花，避免火花翻倍。
         *
         * <p>兜底走 {@link FlameEffects#burning} 的既有观感 —— <b>与改动前逐值相同</b>。</p>
         */
        private void syncFirstPersonFallback(boolean firstPersonSelf) {
            if (ended) return;
            if (firstPersonSelf) {
                if (firstPersonFallback == null) {
                    firstPersonFallback = FlameEffects.burning(target, 10, 14, remaining);
                }
            } else if (firstPersonFallback != null) {
                firstPersonFallback.stop();
                firstPersonFallback = null;
            }
        }

        /**
         * 偶尔冒一朵短命火花：参数与 {@link BurningSpread} 的 spark 分支一致
         * （小而亮、不长大、活完 {@link #SPARK_LIFE} tick 就消失），复用同一条渲染路径。
         *
         * <p>高度带用改动前的 {@code 0.45 + rand*0.5} 身高（火星溅在身体中上部），一字未改。</p>
         */
        private void spawnSparkIfDue() {
            if (--sparkTimer > 0) return;
            sparkTimer = SPARK_INTERVAL;
            FlameStylePreset style = FlameStylePreset.active();
            if (random.nextFloat() > style.sparkChance) return;

            // 贴在身体中下部、稍微离开体表 → 像从火里溅出来的火星（而不是又一层贴身火）
            double ang = random.nextDouble() * Math.PI * 2.0;
            double r = halfWidth * (0.8 + random.nextDouble() * 0.5);
            double y = bodyHeight * (SPARK_BAND_FREE_MIN + random.nextDouble() * SPARK_BAND_FREE_RAND);

            Flame p = new Flame(Shape.BURNING, target.position(), SPARK_LIFE);
            p.ephemeral = true;
            p.thickness = SPARK_RADIUS * (0.8f + random.nextFloat() * 0.5f);
            p.height = SPARK_HEIGHT * (0.8f + random.nextFloat() * 0.5f);
            p.noiseScale = style.noiseScale * SPARK_DETAIL_SCALE;      // 火花更碎（默认档 = 改动前的 6.0）
            p.flow = style.flow * SPARK_DETAIL_SCALE;                  // 火花窜得更快（默认档 = 改动前的 3.0）
            p.density = 11.0f;
            p.alpha = 0.9f;
            p.brightness = style.brightness;
            p.colorCore = style.core;
            p.colorMid = style.mid;
            p.colorEdge = style.edge;
            p.turbulence = Math.min(0.9f, style.turbulence * PATCH_TURBULENCE_RATIO) + random.nextFloat() * 0.06f;
            p.fadeIn = 1;
            p.fadeOut = 5;
            p.follow(target, y, true);
            p.followOffset = new Vec3(Math.cos(ang) * r, 0.0, Math.sin(ang) * r);
            ACTIVE.add(p);
        }
    }

    // ────────────────────────── 生命周期 ──────────────────────────

    /** 客户端每 tick 调用（见 ShaderRenderRegistrar）。 */
    public static void tick() {
        // 先推进"贴模型着火"：它可能在本 tick 挂上/停掉第一人称的兜底体积火（兜底也是 SPREADS 的成员）
        for (int i = SURFACE.size() - 1; i >= 0; i--) {
            SurfaceBurning s = SURFACE.get(i);
            s.tick();
            if (s.finished()) SURFACE.remove(i);
        }
        // 再推进蔓延簇：它们会新增/长大补丁（补丁本身也在 ACTIVE 里，随后统一老化）
        for (int i = SPREADS.size() - 1; i >= 0; i--) {
            BurningSpread spread = SPREADS.get(i);
            spread.tick();
            if (spread.finished()) SPREADS.remove(i);
        }
        if (ACTIVE.isEmpty()) return;
        for (int i = ACTIVE.size() - 1; i >= 0; i--) {
            Flame f = ACTIVE.get(i);
            f.age++;
            f.life--;
            if (f.life <= 0 || (f.follower != null && !f.follower.isAlive())) {
                ACTIVE.remove(i);
            }
        }
    }

    /** 清空全部效果（停机/切维度用）。 */
    public static void clear() {
        for (SurfaceBurning s : SURFACE) s.stop();
        SURFACE.clear();
        for (BurningSpread spread : SPREADS) spread.stop();
        SPREADS.clear();
        ACTIVE.clear();
    }

    /**
     * 当前淡入淡出系数（0~1）。
     * 用 age / life 两端做斜坡，避免火焰"啪"地出现或消失。
     */
    public static float fade(Flame f) {
        float in  = f.fadeIn  <= 0 ? 1.0f : Math.min(1.0f, f.age / (float) f.fadeIn);
        float out = f.fadeOut <= 0 ? 1.0f : Math.min(1.0f, f.life / (float) f.fadeOut);
        return Math.max(0.0f, Math.min(in, out));
    }
}
