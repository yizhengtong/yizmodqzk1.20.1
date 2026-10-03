package net.minecraft.client.yiz.modelmirror;

import com.google.common.collect.ImmutableMap;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexMultiConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.yiz.lightning.fx.ShockAnchors;
import net.minecraft.client.yiz.lightning.fx.ShockArcs;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * "模型几何镜像"的<b>双写</b>缓冲源 —— 让所有体表特效（贴模型火焰、体表闪电…）的几何
 * <b>等于实体自己的模型几何</b>。
 *
 * <p><b>为什么用双写而不是"把模型再跑一遍"</b>：实体的模型几何本来就是经
 * {@code ModelPart.renderToBuffer} 写进 {@code buffer.getBuffer(实体 RenderType)} 的。
 * 这里把实体渲染用的 {@link MultiBufferSource} 换成"多路复用"版本：同一批顶点除了写进主缓冲，
 * <b>再抄一份</b>给每个激活的消费者（{@link MirrorLayer}）—— 顶点数据一字不改（UV/法线/光照/overlay
 * 全部保留），只把顶点色换成"白 + 该层本帧 alpha"）。好处：模型只求值一次（动画/骨骼/GeckoLib
 * 都只跑一遍），几何天然包含四肢/头部/盔甲层等所有实际画出来的部分 —— 而不是"跟着碰撞箱"。</p>
 *
 * <p><b>多消费者</b>：消费者在 {@link ModelMirrorLayers} 里登记。每个实体每帧会问每个消费者
 * "这个实体现在要不要你这一层"（{@link MirrorLayer#prepare}），要几层就抄几份，各自进自己的
 * {@link MirrorChannel}（独立 BufferBuilder/RenderType/uniform），互不干扰 ——
 * 所以"同一实体既着火又带电"时两份都会画。</p>
 *
 * <p><b>第二条（与"层"并列的）下游：锚点采集</b>。围弧（编号 4）要的不是"把模型再画一遍"，
 * 而是"模型表面到底在哪几个位置" —— 它需要一小撮模型顶点当弧的锚点。
 * 这条需求<b>不走 {@link MirrorLayer}/{@link MirrorChannel}</b>（那条路要 RenderType、要写顶点缓冲、
 * 要刷帧），而是由 {@link ShockArcs#beginFrame} 给出一个"只看不写"的顶点接收器
 * （{@code ShockAnchors.Tap}），挂在本类 {@link #getBuffer} 返回的多路复用链上：
 * 顶点照旧只写一份进主缓冲，接收器顺手记下坐标与 UV、不占任何缓冲也不画任何东西。
 * 于是"贴模型着火"（层）与"围弧锚点"（接收器）可以同时生效、各自独立开关、互不影响。</p>
 *
 * <p><b>锚点接收器是"逐层"给的</b>（2026-10-05 起）：{@link #getBuffer} 拿到 {@code type} 时调
 * {@code ShockAnchors.Tap#forType(type)}，取回<b>该层专属</b>的接收器 —— 它绑定的是<b>该层自己那张贴图</b>
 * 的 CPU 侧 alpha 位图，用来判"这个面在贴图上到底有没有画到"（判据与描边镂空同源，见
 * {@code ShockAnchors} 类注释）。为什么必须逐层：一次渲染里本体层 / 发光覆层 / 盔甲层的 UV
 * 各自属于自己那张图，共用一份判据会互相串味；而且分层的接收器让"两层交叉着写顶点"也不出错。
 * 这一步是纯查表（{@code LockOutlineLayerTexture.resolve} + {@code LockOutlineAlphaMask.get} 都带缓存），
 * 不分配、不碰 GL。</p>
 *
 * <p><b>与描边管线的关系（零侵入）</b>：本类不修改 {@code LockOutlineBufferSource} 与
 * {@code EntityRenderDispatcherOutlineMixin}。本类自身是 {@link MultiBufferSource.BufferSource}
 * 子类，所以描边那条 {@code replaced instanceof BufferSource} 的判断在包住本类时依然成立；
 * 描边自己的 fill 顶点写的是它内部的 fill 缓冲，<b>永远不会经过本类的 getBuffer</b>，
 * 于是两条 pass 各自只收到一份几何，互不干扰。</p>
 *
 * <p><b>状态是惰性读取的</b>：{@code wrap(...)} 无条件把主缓冲换成单例（不做任何实体判断），
 * 真正的"这个实体有哪些层激活"在 {@code begin(...)}（实体渲染前）与 {@code getBuffer} 时才查 ——
 * 因此不依赖 mixin 注入点之间的先后顺序（@Inject HEAD 与 @ModifyVariable HEAD 谁先谁后都不影响结果）。</p>
 */
public final class ModelMirrorBufferSource extends MultiBufferSource.BufferSource {

    private static final Logger LOGGER = LoggerFactory.getLogger("YizModelMirror");

    private static final ModelMirrorBufferSource INSTANCE = new ModelMirrorBufferSource();

    /** 每个渲染过的实体一个批次（key 用引用比较）。批次跨帧保留，通道里的 BufferBuilder 因此能复用容量。 */
    private static final Map<Entity, Batch> BATCHES = new IdentityHashMap<>();

    /** 当前正在渲染的实体批次；{@code null} = 该实体本帧没有任何激活层。用 List 当栈（允许存 null）。 */
    private static final List<Batch> STACK = new ArrayList<>();

    /**
     * 与 {@link #STACK} <b>平行</b>的"上一层 delegate"栈（同样是 List 当栈，允许存 null）。
     *
     * <p>实体渲染可以嵌套：实体的渲染器在自己的渲染过程里再经由
     * {@code EntityRenderDispatcher.render} 渲染别的实体（乘客/坐骑/其它 MOD 的渲染层），
     * 而每一层都会各自调一次 {@link #wrap} 把形参换成这个单例。旧写法只给 {@code delegate} 赋值、
     * {@link #end()} 也只还原 {@code current} —— 于是<b>内层</b>换了另一个缓冲源时，
     * 外层实体<b>剩下</b>的模型顶点会继续写进内层的缓冲源里（几何进了别人的目标，
     * 本类 {@code getBuffer} 里所有基于 delegate 的判断也一起错位）。
     * 现在 wrap 压栈、end 出栈，嵌套几层就还原几层。</p>
     */
    private static final List<MultiBufferSource> DELEGATE_STACK = new ArrayList<>();

    /**
     * 当前实体渲染用的"真实"主缓冲源。<b>只要求是 {@link MultiBufferSource}</b>：
     * 原版队伍发光给的是 {@code OutlineBufferSource}（非 BufferSource），其它 MOD 也可能插一层
     * 自己的包装 —— 见 {@link #wrap(MultiBufferSource)}。
     */
    private static MultiBufferSource delegate;
    private static Batch current;

    private ModelMirrorBufferSource() {
        // 父类构造只为拿到"按 RenderType 攒一批 + endBatch 刷帧"的机制；本类自己的 builder 不用
        // （getBuffer 全部转发给 delegate），真正用到它的是每个通道。
        super(new BufferBuilder(256), ImmutableMap.<RenderType, BufferBuilder>of());
    }

    // ────────────────────────── mixin 入口 ──────────────────────────

    /**
     * 实体渲染前调用（相机相对位置 + 渲染朝向来自 {@code EntityRenderDispatcher.render} 的参数）。
     *
     * @param dx/dy/dz 实体相对相机的渲染位置（含插值），加回相机世界坐标即为实体脚部世界坐标
     * @param yaw      实体渲染偏航（度）：各消费者据此把采样点反旋转到身体坐标系
     * @return 本次决策的摘要字符串（临时诊断用，见 {@link MirrorDiag}）——
     *         {@code "OK..."} 表示双写通道已打开，其余值直接说明在哪一道闸门被挡下
     */
    public static String begin(Entity entity, double dx, double dy, double dz, float yaw) {
        STACK.add(current);          // 嵌套渲染（如玩家肩上的鹦鹉）保存/恢复外层状态
        current = null;
        if (entity == null) return "实体为 null";

        // 只认关卡透视投影（判据见 #isLevelPerspective）：GUI 里的实体预览一律跳过
        if (!isLevelPerspective()) {
            Matrix4f m = RenderSystem.getProjectionMatrix();
            return "非关卡透视投影 → 跳过（m23=" + fmt(m.m23()) + " m32=" + fmt(m.m32()) + " m33=" + fmt(m.m33()) + "）";
        }

        Batch batch = BATCHES.get(entity);
        if (batch == null) {
            batch = new Batch(entity);
            BATCHES.put(entity, batch);
        }
        batch.beginFrame(dx, dy, dz, yaw);
        // ── 围弧（编号 4）的锚点采集：本帧这个实体要不要顺手收一份模型顶点坐标 ──
        // 与"镜像层"是<b>并列</b>的两件事：它不走 MirrorChannel（不写任何顶点缓冲、不要 RenderType、
        // 不画东西），只是一个挂在多路复用器末端的"只看不写"的接收器（见 ShockAnchors.Tap）。
        // 只有"正在感电 + 开关 4 开着"的实体才会拿到它，其余实体一个顶点都不会被额外收走。
        batch.anchorTap = ShockArcs.beginFrame(entity);

        if (!batch.hasActiveChannel() && batch.anchorTap == null) {
            return "该实体本帧没有任何体表特效状态（模型顶点不会被抄）";
        }
        current = batch;
        return "OK（双写通道已打开，激活层=" + batch.activeSummary()
                + (batch.anchorTap != null ? "，锚点采集=开" : "") + "）";
    }

    /** 实体渲染结束：恢复外层状态（批次留在表里，等 AFTER_ENTITIES 统一刷帧）。 */
    public static void end() {
        current = STACK.isEmpty() ? null : STACK.remove(STACK.size() - 1);
        // delegate 与 current 成对还原（理由见 DELEGATE_STACK）：嵌套实体渲染改用别的缓冲源时，
        // 外层剩下的顶点必须回到外层自己的 delegate 上，否则会写进内层的目标。
        delegate = DELEGATE_STACK.isEmpty() ? null : DELEGATE_STACK.remove(DELEGATE_STACK.size() - 1);
    }

    /**
     * 把实体渲染用的主缓冲换成"多路复用双写"版本。
     *
     * <p><b>为什么不再要求 delegate 是 {@link MultiBufferSource.BufferSource}</b>：实体渲染拿到的缓冲源
     * 并不保证是真 BufferSource —— 原版"队伍发光/自定义描边"那条路传的是
     * {@code OutlineBufferSource}（只实现 MultiBufferSource），其它 MOD 也可能再插一层包装。
     * 旧实现用 {@code instanceof BufferSource} 当闸门，这些情况会<b>整条静默失效</b>
     * （不抄几何、也不报错，外部表现就是"完全看不到效果"）。现在无条件包一层：
     * {@link #getBuffer} 一律转发给 delegate 并额外抄一份；
     * {@code endBatch / endBatch(RenderType) / endLastBatch} 只在 delegate 恰好是 BufferSource 时转发
     * （不是的话它本来也没有这些方法可转发），各消费者自己的通道从不依赖它们刷帧。</p>
     *
     * <p><b>为什么本类仍然 extends BufferSource</b>：描边管线的判断是
     * {@code replaced instanceof MultiBufferSource.BufferSource}（见
     * {@code EntityRenderDispatcherOutlineMixin}）—— 本类必须保持是 BufferSource，
     * 否则那一处会对<b>所有</b>实体退化成"不描边"。而各通道的 flush 用的是自己的
     * {@link MirrorChannel}（自己的 BufferBuilder），与"delegate 是不是 BufferSource"无关。</p>
     */
    public static MultiBufferSource wrap(MultiBufferSource source) {
        // ── 先压栈、再赋值，且"无论 source 是什么都压一层" ──
        // wrap 与 end 是同一个方法上的两个注入点（@ModifyVariable HEAD / @Inject RETURN），
        // 一次实体渲染各跑一次，所以这样必定成对；反过来，如果只在"确实要换"时才压栈，
        // source == null / source 已经是本单例（二次包装）这两条早退路径就会让栈错位，
        // 之后所有 end() 都会还原到别人的 delegate 上（比不还原更糟）。
        DELEGATE_STACK.add(delegate);
        if (source == null) return null;
        if (source == INSTANCE) return source;   // 二次包装（同一帧里嵌套调用自己）→ 原样返回，别把 delegate 指成自己
        delegate = source;
        if (MirrorDiag.first("mm:wrap:" + source.getClass().getName())) {
            LOGGER.info("[ModelMirror] wrap 收到 MultiBufferSource: 类={}（是否 BufferSource={}）→ 已换成模型几何双写源",
                    source.getClass().getName(), source instanceof MultiBufferSource.BufferSource);
        }
        return INSTANCE;
    }

    /** 双写源单例（临时诊断用：判断 mixin 拿到的形参是不是已经被换成本类）。 */
    public static MultiBufferSource instance() {
        return INSTANCE;
    }

    /** 本帧收集到的批次快照（AFTER_ENTITIES 逐个刷帧用；拷贝一份，遍历时可安全删表项）。 */
    public static List<Batch> batchesSnapshot() {
        return new ArrayList<>(BATCHES.values());
    }

    /** 丢弃某实体的批次（所有层都不再激活 / 实体已移除）。 */
    static void forget(Batch batch) {
        batch.discardAll();
        BATCHES.remove(batch.entity);
    }

    /**
     * 只认关卡透视投影：背包/JEI 等 GUI 里的实体预览是正交投影 + GUI 空间矩阵，
     * 本 pass 的"视图空间顶点 + 深度截断"假设在那里全部不成立（会画出一团乱码），直接跳过。
     *
     * <p><b>判据（JOML 的 m&lt;列&gt;&lt;行&gt; 约定，务必看清）</b>：透视投影
     * {@code new Matrix4f().setPerspective(fov, aspect, near, far)} 把 -1 放在 <b>m23</b>
     * （m23 = -1、m33 = 0，w = -z），而 <b>m32 是 z 的线性映射项</b>
     * （= 2·near·far/(near-far)；near=0.05、far=1000 时 ≈ -0.100，<b>永远不可能是 -1</b>）；
     * 正交投影 {@code setOrtho(...)} 是仿射矩阵（m23 = 0、m33 = 1）。</p>
     *
     * <p>旧实现判的是 {@code |m32 + 1| < 1e-3}（那是"m32 = -1"的旧矩阵约定），在关卡里恒为 false，
     * 于是每次 {@code begin()} 都提前返回 → 一个顶点都没抄到。现在同时要求 |m23| ≈ 1 且 m33 ≈ 0：
     * 透视成立，正交 / 单位阵 / 未初始化都不成立。</p>
     */
    private static boolean isLevelPerspective() {
        Matrix4f m = RenderSystem.getProjectionMatrix();
        return Math.abs(Math.abs(m.m23()) - 1.0f) < 1.0e-3f && Math.abs(m.m33()) < 1.0e-3f;
    }

    private static String fmt(float v) {
        return String.format(Locale.ROOT, "%.4f", v);
    }

    // ────────────────────────── "只抄实体本体几何"：排除影子 pass ──────────────────────────

    /**
     * vanilla 的影子贴图路径。{@code EntityRenderDispatcher.SHADOW_RENDER_TYPE} 用的就是它
     * （{@code EntityRenderDispatcher.java:59} = {@code RenderType.entityShadow(new ResourceLocation("textures/misc/shadow.png"))}），
     * 影子由同一个 render 方法里的 {@code renderShadow} 画出（{@code :146}）—— 而那个
     * {@code EntityRenderDispatcher.render} 正是本类包装的入口，所以影子必然经过本类的 {@code getBuffer}。
     */
    private static final ResourceLocation SHADOW_TEXTURE =
            ResourceLocation.fromNamespaceAndPath("minecraft", "textures/misc/shadow.png");

    /**
     * vanilla 影子 RenderType 的<b>单例</b>。{@code RenderType.ENTITY_SHADOW} 是 {@code Util.memoize}
     * 缓存出来的（{@code RenderType.java:87-90}）：同一个贴图路径永远返回同一个对象，
     * 所以这里能安全地用 {@code ==} 做身份比较（不解析字符串、不反射、零分配）。
     */
    private static final RenderType VANILLA_SHADOW = RenderType.entityShadow(SHADOW_TEXTURE);

    /**
     * 影子判定的缓存：{@link RenderType} 全是 memoize 出来的单例，按引用缓存即可，
     * 条目数 = 游戏里出现过的 RenderType 数（个位数）。名字兜底判据要读 {@code toString()}
     * （{@code CompositeRenderType.toString()} 会把整串 CompositeState 拼出来，见 {@code RenderType.java:513-515}），
     * 不能每帧每实体都算 —— 缓存后每个 RenderType 只算一次。只在渲染线程访问，无需同步。
     */
    private static final Map<RenderType, Boolean> SHADOW_FLAGS = new IdentityHashMap<>();

    /**
     * 是否是"非本体"的影子 pass —— 影子几何<b>绝不能</b>抄进任何特效缓冲。
     *
     * <p><b>为什么必须排除</b>：vanilla 影子的顶点格式也是 {@code NEW_ENTITY}
     * （{@code RenderType.java:89}，与实体模型同格式 —— 影子着色器同样要吃 UV0/光照/overlay），
     * 于是它会照样通过下面那条"只抄 NEW_ENTITY"的判据被抄一份：影子是一整片贴地/贴墙的实心四边形，
     * 被特效着色器画出来就是地上一块"特效色的影子"，是明显的错误几何。
     * 影子只是投影、不是实体体表，所以这里明确排除，只抄本体。</p>
     *
     * <p><b>判据（两级）</b>：</p>
     * <ol>
     *   <li><b>身份比较</b>（主判据）：{@code type == VANILLA_SHADOW}。vanilla 所有实体影子共用
     *       {@code EntityRenderDispatcher} 里那一个 {@code static final} 常量（{@code :59}），
     *       而 memoize 保证我这边重新构造的调用拿到的是同一个对象 —— 精确、O(1)、与字符串格式无关。</li>
     *   <li><b>名字兜底</b>：万一日后 / 某个 MOD 用别的影子贴图再建一个同族 RenderType
     *       （贴图是 memoize 的 key，换了贴图就是另一个实例），它的名字仍然固定是 {@code entity_shadow}
     *       （{@code RenderType.java:89} 里 {@code create} 的第一个参数），一并排除。</li>
     * </ol>
     *
     * <p><b>为什么不排除别的</b>：{@code entity_translucent} 是玩家皮肤的半透明叠层，
     * {@code entity_cutout_no_cull} 是本体基础层，{@code armor_cutout_no_cull}（盔甲）、
     * {@code eyes}（发光眼）、{@code entity_decal}（苦力怕充能 / 铁傀儡裂纹）等全是<b>体表几何</b>，
     * 必须照抄（盔甲/手持物另由 {@link MirrorExclusion} 在"渲染期标志"那一层排除，不是靠 RenderType 名字）；
     * 而闪光（{@code glint*} / {@code entity_glint*}，格式 {@code POSITION_TEX}）与描边
     * （{@code outline}，格式 {@code POSITION_COLOR_TEX}）根本不是 {@code NEW_ENTITY} 格式，
     * 早已被下面那条格式判据挡住，不需要在这里再列一遍。</p>
     *
     * <p><b>2026-10-04 起本判据由描边管线的"门禁 2"共用</b>（{@code LockOutlineBufferSource#getBuffer}）：
     * 描边 fill 通道不经过本类，所以那边必须自己挡一次 —— 共用同一份判据，避免两处判据漂移。</p>
     */
    public static boolean isShadowPass(RenderType type) {
        Boolean cached = SHADOW_FLAGS.get(type);
        if (cached != null) return cached;
        boolean shadow = type == VANILLA_SHADOW || "entity_shadow".equals(renderTypeName(type));
        SHADOW_FLAGS.put(type, shadow);
        return shadow;
    }

    /**
     * 取 {@link RenderType} 的名字。{@code CompositeRenderType.toString()} 形如
     * {@code "RenderType[entity_shadow:CompositeState[...]]"}，而名字本身不含 {@code [} / {@code :}，
     * 所以取"第一个 {@code [} 之后、其后第一个 {@code :} 之前"这一段最稳（不写死外层包装格式）；
     * 普通 {@code RenderType.toString()} 直接就是名字，也会落到同一条返回路径。取不到就原样返回。
     */
    private static String renderTypeName(RenderType type) {
        String s = type.toString();
        int open = s.indexOf('[');
        int colon = s.indexOf(':', open + 1);
        return open >= 0 && colon > open + 1 ? s.substring(open + 1, colon) : s;
    }

    /** 是否是某个消费者自己的 RenderType（自己的 pass 绝不双写自己，否则会无限递归抄写）。 */
    private static boolean isOwnRenderType(RenderType type) {
        for (MirrorLayer layer : ModelMirrorLayers.ALL) {
            if (type == layer.renderType()) return true;
        }
        return false;
    }

    // ────────────────────────── MultiBufferSource ──────────────────────────

    @Override
    public VertexConsumer getBuffer(RenderType type) {
        // ── 临时诊断：这一行决定"几何到底有没有机会被抄" ──
        // 用按身份限流（不是拼字符串 key）：本方法每帧每模型部件都会被调用一次，热路径上不能拼字符串。
        if (MirrorDiag.firstIdentity(type)) {
            LOGGER.info("[ModelMirror] getBuffer: renderType={} format={} 是否 NEW_ENTITY={} 是否本机制自己的RenderType={}"
                            + " delegate={} 当前实体批次={}",
                    type, MirrorDiag.formatName(type == null ? null : type.format()),
                    type != null && type.format() == DefaultVertexFormat.NEW_ENTITY,
                    type != null && isOwnRenderType(type),
                    delegate == null ? "null" : delegate.getClass().getSimpleName(),
                    current == null ? "无（该实体没有激活层 / 被闸门挡下）" : current.activeSummary());
        }
        if (delegate == null) return super.getBuffer(type);
        Batch batch = current;
        if (batch == null || type == null) return delegate.getBuffer(type);
        // 只双写"实体模型几何"：NEW_ENTITY 是模型顶点格式，文字/线框/闪光/粒子格式不同 → 原样放过；
        // 各消费者自己的 pass 绝不递归双写。
        if (type.format() != DefaultVertexFormat.NEW_ENTITY || isOwnRenderType(type)) {
            return delegate.getBuffer(type);
        }
        // 影子同样是 NEW_ENTITY 格式、但它是"非本体"的投影 → 排除，别在地面/墙上烧出一块特效色的影子。
        // 判据见 #isShadowPass（身份比较为主，名字兜底）。注意这里只放弃"多抄一份"，
        // 主缓冲那一份照旧由 delegate 正常渲染，影子该显示还显示。
        if (isShadowPass(type)) {
            // ── 临时诊断：影子 pass 确实被挡下了（全局只打一条，key 是常量，避免每次调用都拼 RenderType 字符串）──
            if (MirrorDiag.first("mm:skipShadow")) {
                LOGGER.info("[ModelMirror] 排除影子 pass（不抄进任何特效缓冲）: renderType={} —— 影子与模型同为 NEW_ENTITY 格式，"
                        + "但只是投影、不是实体体表几何", type);
            }
            return delegate.getBuffer(type);
        }
        // 手持物 / 掉落物 / 展示框物品 / 盔甲：是"外挂网格"而不是实体体表（用户要求物品不参与灼烧），
        // 判据与理由见 MirrorExclusion（渲染期标志，不靠 RenderType 名字 —— 物品与本体用的是同一批名字）。
        if (MirrorExclusion.isExcluded()) {
            if (MirrorDiag.first("mm:skipEquipment:" + MirrorExclusion.reason())) {
                LOGGER.info("[ModelMirror] 排除装备/物品 pass（不抄进任何特效缓冲）: 原因={} renderType={} —— "
                        + "物品与本体共用同一批 RenderType 名字，只能靠渲染期标志区分",
                        MirrorExclusion.reason(), type);
            }
            return delegate.getBuffer(type);
        }
        // 主缓冲那一份照旧由 delegate 写；每个<b>本帧激活</b>的消费者的那一份写进各自的通道（自己的 BufferBuilder），
        // 围弧（编号 4）的锚点接收器（若有）也挂在同一条多路复用链上 —— 它只看不写，不占任何缓冲。
        // 注意必须按 active() 过滤：同一实体的不同层各自生效（只着火 / 只带电 / 两者都有），
        // 没生效的层连通道都可能还没建（byLayer[i] == null）—— 直接抄会 NPE。
        VertexConsumer primary = delegate.getBuffer(type);
        // 锚点接收器（本帧该实体正在感电且有开关 4 时非 null）：见 ShockAnchors.Tap 与上面的 begin()。
        // 这里取的是「<b>该层专属</b>」的接收器（按 RenderType 身份从主 Tap 的池里取，见 Tap#forType）：
        // 它在本层 getBuffer 时绑定本层贴图的 alpha 位图 ⇒ 本体层/发光覆层/盔甲层各用各的 UV 与贴图做判据，
        // 不会串味。池满（异常多的 RenderType 实例）时主 Tap 会给出一个永不绑定的兜底接收器（= 该层不过滤）。
        VertexConsumer tap = batch.anchorTap == null ? null : batch.anchorTap.forType(type);
        int activeCount = 0;
        for (int i = 0; i < batch.channelCount(); i++) {
            MirrorChannel ch = batch.channelAt(i);
            if (ch != null && ch.active()) activeCount++;
        }
        if (activeCount == 0 && tap == null) return primary;   // 理论不可达：current 只在有活干时才设置
        if (activeCount == 0) {
            // 只收锚点（该实体只在感电、没着火）：锚点接收器 + 本体，走 vanilla 的 Double 组合（不额外分配数组）
            return VertexMultiConsumer.create(tap, primary);
        }
        if (activeCount == 1 && tap == null) {
            for (int i = 0; i < batch.channelCount(); i++) {
                MirrorChannel ch = batch.channelAt(i);
                if (ch != null && ch.active()) {
                    // 单层：走 vanilla 的 Double 组合（不额外分配数组）
                    return VertexMultiConsumer.create(ch.copy(), primary);
                }
            }
        }
        VertexConsumer[] delegates = new VertexConsumer[(tap != null ? 1 : 0) + activeCount + 1];
        int n = 0;
        // 锚点接收器放最前（它不写缓冲，位置无所谓；放最前让"抄了几份"的阅读顺序与诊断一致）
        if (tap != null) delegates[n++] = tap;
        for (int i = 0; i < batch.channelCount(); i++) {
            MirrorChannel ch = batch.channelAt(i);
            if (ch != null && ch.active()) delegates[n++] = ch.copy();
        }
        delegates[n] = primary;   // 主缓冲放最后：顶点按"各特效副本 → 本体"的顺序写，本体永远是最后一份
        return VertexMultiConsumer.create(delegates);
    }

    // BufferSource 语义原样转发：包一层之后别人的 endBatch/endLastBatch 行为必须和不包时一致。
    // delegate 不是 BufferSource（如原版 OutlineBufferSource）时没有可转发的方法 —— 那些管线自己刷自己的缓冲，
    // 本类不会（也不该）替它刷帧。
    @Override
    public void endBatch() {
        if (delegate instanceof MultiBufferSource.BufferSource bs) bs.endBatch();
    }

    @Override
    public void endBatch(RenderType type) {
        if (delegate instanceof MultiBufferSource.BufferSource bs) bs.endBatch(type);
    }

    @Override
    public void endLastBatch() {
        if (delegate instanceof MultiBufferSource.BufferSource bs) bs.endLastBatch();
    }

    // ────────────────────────── 单实体批次 ──────────────────────────

    /**
     * 单个实体的批次：持有一组通道（按 {@link ModelMirrorLayers#ALL} 的顺序，几何写入/绘制顺序因此稳定）。
     *
     * <p>通道按层号存在数组里而不是 Map：层是固定的少数几个，数组让"遍历顺序 = 注册顺序"，
     * 也避免每帧每实体分配集合（Map/List 的迭代器都会分配）。</p>
     */
    public static final class Batch {

        final Entity entity;
        /** 下标 = {@link ModelMirrorLayers#ALL} 的下标；{@code null} = 该层从未对这条实体生效过。 */
        private final MirrorChannel[] byLayer = new MirrorChannel[ModelMirrorLayers.ALL.size()];

        /**
         * 本帧"只收锚点、不画模型"的顶点接收器（围弧 / 编号 4）；
         * {@code null} = 本帧这个实体不收锚点（没感电 / 开关 4 关着 / 实体渲染没走到这里）。
         *
         * <p>它<b>不是</b> {@link MirrorLayer}：不占 MirrorChannel、不要 RenderType、不画任何顶点，
         * 只是挂在 {@link #getBuffer} 的多路复用链末端接住模型顶点的最终坐标
         * （见 {@code ShockAnchors.Tap}）。这个字段持有的是<b>主 Tap</b>（工厂/标记，自己不接顶点），
         * 真正的接收器由 {@code ShockAnchors.Tap#forType(RenderType)} 按层给出（每层绑自己那张
         * 贴图的 alpha 位图）。锚点表本身归 {@code ShockArcs} 持有，
         * 所以即便这个 Batch 被回收，下一帧也能接着往同一张表里采。</p>
         */
        ShockAnchors.Tap anchorTap;

        Batch(Entity entity) {
            this.entity = entity;
        }

        Entity entity() {
            return entity;
        }

        int channelCount() {
            return byLayer.length;
        }

        MirrorChannel channelAt(int index) {
            return byLayer[index];
        }

        /** 本帧是否有任何一层对这条实体生效（没有 = 批次可以丢了）。 */
        boolean hasActiveChannel() {
            for (MirrorChannel ch : byLayer) {
                if (ch != null && ch.active()) return true;
            }
            return false;
        }

        /** 本帧这条实体是否要收锚点（围弧编号 4）——批次回收判据里与 {@link #hasActiveChannel()} 并列。 */
        boolean wantsAnchors() {
            return anchorTap != null;
        }

        /** 当前激活层的短名串（诊断日志用）。 */
        String activeSummary() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < byLayer.length; i++) {
                MirrorChannel ch = byLayer[i];
                if (ch == null || !ch.active()) continue;
                if (sb.length() > 0) sb.append('+');
                sb.append(ModelMirrorLayers.ALL.get(i).id());
            }
            return sb.length() == 0 ? "无" : sb.toString();
        }

        /**
         * 实体渲染前：让每个消费者判断本帧要不要自己，并更新通道状态。
         *
         * <p>先把所有旧通道标记"本帧未激活"，再逐层覆盖 —— 这样不需要每帧分配集合，
         * 且"上一帧生效、本帧失效"的通道会在 {@link MirrorChannel#deactivate()} 里丢掉残留顶点。</p>
         */
        void beginFrame(double dx, double dy, double dz, float yaw) {
            for (MirrorChannel ch : byLayer) {
                if (ch != null && ch.active()) ch.deactivate();
            }
            List<MirrorLayer> layers = ModelMirrorLayers.ALL;
            for (int i = 0; i < layers.size(); i++) {
                MirrorLayer layer = layers.get(i);
                MirrorFrame frame = layer.prepare(entity, dx, dy, dz, yaw);
                if (frame == null) continue;
                MirrorChannel ch = byLayer[i];
                if (ch == null) {
                    ch = new MirrorChannel(layer, entity);
                    byLayer[i] = ch;
                }
                ch.activate(frame);
            }
        }

        /** 丢弃整批（实体已移除 / 所有层都不再激活）。 */
        void discardAll() {
            anchorTap = null;
            for (MirrorChannel ch : byLayer) {
                if (ch != null) {
                    ch.deactivate();
                    ch.discard();
                }
            }
        }
    }
}
