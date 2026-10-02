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
        // ── 临时诊断（定位"贴模型火完全看不见"）：状态到底建起来了没有、建在谁身上 ──
        LOGGER.info("[FlameSurface] 状态已创建: entity={} spreadTicks={} life={} 当前贴模型着火实体数={}",
                net.minecraft.client.yiz.modelmirror.MirrorDiag.entity(target),
                spreadTicks, life, SURFACE.size());
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
        private static final float SPARK_CHANCE = 0.28f;
        /**
         * 自己着火 + 第一人称时的冻结时刻（tick，40 ≈ 2.0 秒）。
         * 第一人称下火焰铺满视野，再继续蔓延只会越叠越亮、把本来模糊的边界顶成刺眼的硬边；
         * 到点冻结（不再新增、不再长大），保留当前形态即可。
         */
        private static final int LOCAL_FIRST_PERSON_FREEZE = 40;

        private final Entity target;
        private final List<Flame> patches = new ArrayList<>();
        private final int maxPatches;
        private final int spreadInterval;
        private final int life;
        private final net.minecraft.util.RandomSource random = net.minecraft.util.RandomSource.create();
        private final float halfWidth;
        private final float bodyHeight;

        private int age;
        private boolean ended;
        private boolean frozen;

        BurningSpread(Entity target, int maxPatches, int spreadInterval, int life) {
            this.target = target;
            this.maxPatches = Math.max(1, Math.min(24, maxPatches));
            this.spreadInterval = Math.max(1, spreadInterval);
            this.life = life;
            this.halfWidth = (float) Math.max(target.getBbWidth() * 0.5, 0.25);
            this.bodyHeight = (float) Math.max(target.getBbHeight(), 0.6);
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
            // 约三成概率改成一朵"火花"：更小、更亮、短命，且不占主火焰的名额（只进 ACTIVE）
            boolean spark = near != null && random.nextFloat() < SPARK_CHANCE;

            double localX;
            double localZ;
            double y;
            if (near == null) {
                //  起燃点：贴着身体圆柱面（半径约 0.35~0.65 倍半宽），高度偏下 → 从下往上烧
                double ang = random.nextDouble() * Math.PI * 2.0;
                double r = halfWidth * (0.35 + random.nextDouble() * 0.30);
                localX = Math.cos(ang) * r;
                localZ = Math.sin(ang) * r;
                y = bodyHeight * 0.22;
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
                //  蔓延上限：只烧<b>下半身</b>（用户反馈：烧满全身很丑），y 最高 ≈ 0.48 倍身高，
                //  向上步长也缩小，补丁集中在腰以下
                y = clamp(near.followYOffset + bodyHeight * (0.05 + random.nextDouble() * 0.12),
                          0.04 * bodyHeight, 0.48 * bodyHeight);
            }

            Flame p = new Flame(Shape.BURNING, target.position(), spark ? SPARK_LIFE : 64);
            p.turbulence = 0.30f + random.nextFloat() * 0.06f;
            p.follow(target, y, true);
            p.followOffset = new Vec3(localX, 0.0, localZ);

            if (spark) {
                p.ephemeral = true;
                p.thickness = SPARK_RADIUS * (0.8f + random.nextFloat() * 0.5f);
                p.height = SPARK_HEIGHT * (0.8f + random.nextFloat() * 0.5f);
                p.noiseScale = 6.0f;
                p.flow = 3.0f;
                p.density = 11.0f;      // 小而亮 → 像飞溅的火星
                p.alpha = 0.9f;
                p.fadeIn = 1;
                p.fadeOut = 5;
                ACTIVE.add(p);          // 只进 ACTIVE：不占主火焰名额、也不作为蔓延起点
                return;
            }

            p.thickness = MIN_RADIUS * (0.85f + random.nextFloat() * 0.3f);
            p.height = MIN_HEIGHT * (0.85f + random.nextFloat() * 0.3f);
            p.noiseScale = 4.4f + random.nextFloat() * 0.8f;   // 小团火 → 细节更碎
            p.flow = 2.2f + random.nextFloat() * 0.5f;         // 小火苗抖得快
            p.density = 6.5f;                                  // 比原来淡：盖住身体但不遮模型
            p.alpha = 0.85f;
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
     *       保证第一人称仍然"四周一圈火"（它自带 2.0s 冻结、中心剔除与火花）；</li>
     *   <li>切回第三人称：兜底体积火停掉（避免"模型火 + 碰撞箱火"双重叠加）、蔓延上限放开
     *       （第一人称的视野/帧率约束不存在了，让火焰继续爬满，否则看起来像卡住）。</li>
     * </ol>
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
         * 偶尔冒一朵短命火花：参数与 {@link BurningSpread} 的 spark 分支完全一致
         * （小而亮、不长大、活完 {@link #SPARK_LIFE} tick 就消失），复用同一条渲染路径。
         */
        private void spawnSparkIfDue() {
            if (--sparkTimer > 0) return;
            sparkTimer = SPARK_INTERVAL;
            if (random.nextFloat() > SPARK_CHANCE) return;

            // 贴在身体中上部、稍微离开体表 → 像从火里溅出来的火星（而不是又一层贴身火）
            double ang = random.nextDouble() * Math.PI * 2.0;
            double r = halfWidth * (0.8 + random.nextDouble() * 0.5);
            double y = bodyHeight * (0.45 + random.nextDouble() * 0.5);

            Flame p = new Flame(Shape.BURNING, target.position(), SPARK_LIFE);
            p.ephemeral = true;
            p.thickness = SPARK_RADIUS * (0.8f + random.nextFloat() * 0.5f);
            p.height = SPARK_HEIGHT * (0.8f + random.nextFloat() * 0.5f);
            p.noiseScale = 6.0f;
            p.flow = 3.0f;
            p.density = 11.0f;
            p.alpha = 0.9f;
            p.turbulence = 0.30f + random.nextFloat() * 0.06f;
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
