package net.minecraft.client.yiz.lightning.fx;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.yiz.lightning.geometry.ArcGeometry;
import net.minecraft.client.yiz.lightning.util.ShaderTimeUtil;
import net.minecraft.client.yiz.modelmirror.MirrorDiag;
import net.minecraft.client.yiz.tool.FxDebugSwitches;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * <b>围弧（编号 4）</b>：感电期间在实体<b>身体外的空间里</b>跳动闪烁的短电弧。
 *
 * <h2>它是什么（一句话）：独立的三维短弧几何，但端点取自"模型自己写出的顶点"</h2>
 * <p>两个前身各自有一个致命缺点，本类把它们拼起来：</p>
 * <ul>
 *   <li><b>旧 4 号（用户满意的那版）</b>：几何是独立的三维线段（好看的"空间电弧"），
 *       但位置按<b>世界包围盒</b>算 ⇒ 别的模组把模型挪走、击飞倒下，弧不跟着；</li>
 *   <li><b>第五轮（被否的那版）</b>：几何是<b>模型顶点本身</b> ⇒ 位置天然正确，
 *       但"哪些像素能发光"由"哪些皮肤像素可见"决定 ⇒ 观感永远是<b>贴在皮肤上的一层蓝色图案</b>。</li>
 * </ul>
 * <p>本类：几何重新变成<b>独立的三维折线</b>（有自己的顶点、飘在身体外 0.05~0.35 格的空间里），
 * 但每条弧的两个端点都<b>挂在 {@link ShockAnchors} 采出来的模型顶点上</b>。
 * 于是"空间电弧"的形态 + "模型在哪弧就在哪"的位置，同时成立。</p>
 *
 * <h2>坐标系：全程"模型顶点所在的那个空间"，没有"实体世界位置"这个概念</h2>
 * <p>锚点就是模型顶点，而模型顶点处在<b>视图空间</b>：实体渲染时 PoseStack 里已经乘过
 * 「视图旋转 + 相机相对平移 + 实体朝向 + 骨骼动画」，且实体渲染期间 {@code ModelViewMat} 是单位阵
 * —— 这两条合起来才解释了"模型为什么能正确地朝向屏幕"（取证见
 * {@code rendertype_flame_surface.vsh} 的坐标系说明）。每条弧只存
 * <b>"锚点下标 + 相对锚点的偏移"</b>，偏移是常量向量 —— 于是：</p>
 * <ul>
 *   <li>每帧渲染时用<b>本帧刚采到的</b>锚点 + 存下来的偏移重新算出两端 ⇒ 弧跟着模型动，
 *       包括被别的模组整体搬走（顶点自己就在新位置写的）、击飞倒下（顶点被转过去了）、
 *       骨骼动画、骑乘偏转、走动转身 —— <b>全都是同一份顶点流，不是复算出来的</b>；</li>
 *   <li><b>不要</b>把弧的顶点再乘 {@code event.getPoseStack().last().pose()}（那是给"相机相对世界坐标"
 *       用的视图矩阵）：锚点里已经有视图旋转了，再乘一遍弧就会跟着镜头转 ——
 *       所以 {@link #emit} 用单位阵写入（见 {@link #IDENTITY} 的注释）。</li>
 * </ul>
 *
 * <h2>锚点只在"贴图真正画到的地方"（2026-10-05：按该层贴图 alpha 过滤）</h2>
 * <p>用户复现（最简例子）："原版监守者有两个耳朵（{@code right_tendril}/{@code left_tendril}），耳朵在模型里
 * 是一块很大的平板，但贴图只画了一小部分，我们却在整块平板上绘制 ⇒ 闪电看起来覆盖了整个耳朵，
 * 变成大耳怪。" 病灶就是锚点直接取模型顶点：平板没画的角落也照样是锚点。</p>
 * <p>现在锚点<b>按面</b>取（每 4 个顶点一个 QUADS 面）：面的 UV 矩形内做 2×2~4×4 小网格采样，
 * 每个采样点查该层贴图的纹素 alpha（{@code ≥ 0.1}，与描边 {@code fsh} 那句 discard 同一阈值），
 * 通过的点用四角位置双线性插值出 3D 位置当锚点；<b>整面一个通过点都没有 ⇒ 这一面不产生任何锚点</b>。
 * 全部判据在 CPU 侧（{@link ShockAnchors} + {@code LockOutlineAlphaMask}/{@code LockOutlineLayerTexture}），
 * <b>本类一个字节的着色器/混合/采样器状态都没动</b>，渲染路径仍是
 * {@code LightningRenderer} + {@code ArcGeometry} + {@code rendertype_lightning_arc}。</p>
 * <p><b>观感参数一个都没改</b>：{@link #ARC_MAX} / {@link #APPARENT_GAIN} / {@link #OFF_MAX} /
 * {@link #SEGMENTS} / 细丝比例等全部原样；变化只发生在"锚点落在哪里"。
 * 锚点表长度照旧被 {@link ShockAnchors#CAPACITY} 的抽稀夹在 80~160，所以弧的数量与密度与上一版一致
 * （离线用真 {@code warden.png} 跑同一套算法：候选点 408 个/帧，抽稀后仍是满表）。</p>
 *
 * <h2>观感参数（每一条弧都是独立的生灭单元）</h2>
 * <ul>
 *   <li><b>寿命</b> {@link #LIFE_MIN}~{@link #LIFE_MAX} 秒，两端各有淡入淡出 ⇒ 不断生灭；</li>
 *   <li><b>数量</b>按"屏幕覆盖率"自适应：模型半径 / 到相机距离 决定目标条数，
 *       上限 {@link #ARC_MAX} 条/实体，并有全场总预算 {@link #ARC_TOTAL_MAX}（多目标时按比例降量）
 *       ⇒ 远处/多目标不会刷爆；</li>
 *   <li><b>偏移</b>沿"该锚点相对模型质心的径向外"推 {@link #OFF_MIN}~{@link #OFF_MAX} 格
 *       （外加 {@link #JITTER} 的随机抖动）⇒ 弧悬在体外那一圈空间里，不贴皮肤；</li>
 *   <li><b>长度</b>夹在 {@link #SEG_MIN}~{@link #SEG_MAX} 格：第二个端点优先取"离第一个锚点最近的
 *       候选锚点"（{@link #NEIGHBOR_TRIES} 次随机试探里挑最近的），于是弧是<b>短的局部弧</b>，
 *       既不会一只手连到另一只脚，也基本不会横穿身体；</li>
 *   <li><b>亮度</b>逐条随机 {@link #BRIGHT_MIN}~{@link #BRIGHT_MAX} 倍（<b>两段长尾</b>：
 *       {@link #BRIGHT_TAIL_FRAC} 的弧更亮更粗 ⇒ "噼啪"的层次），再乘感电状态的整体
 *       强度（{@code ShockEffects.SurfaceShock#strength} 自带 3 tick / 2 tick 两档闪烁）；</li>
 *   <li><b>细丝分支</b>：每条弧以 {@link #FILAMENT_CHANCE} 的概率挂 1~{@link #FILAMENT_MAX} 根
 *       更细更暗的短丝（{@link ArcGeometry#emitShortArc} 的新增参数）⇒ 读起来是"电弧丛"而不是
 *       "几根光棒"；</li>
 *   <li><b>折线抖动</b>由 {@link ArcGeometry#emitShortArc} 的锯齿 spine 提供，随 {@code iTime}
 *       在两个随机布局间插值 ⇒ 每条弧自己也在"噼啪"地扭；{@link #SEGMENTS} 段折线。</li>
 * </ul>
 *
 * <h2>顶点预算（"更多特效"的性能护栏，实测口径见 {@link #logDraw}）</h2>
 * <p>单弧 = 主干 {@code 3 带 × SEGMENTS(6) × 4} = 72 + 细丝 {@code 16/根}。
 * 每实体上限 {@link #ARC_MAX}(44) × 104 = <b>4576 顶点/帧</b>；全场 {@link #ARC_TOTAL_MAX}(140) 最坏
 * ≈ <b>14.6k 顶点/帧</b>，典型（细丝概率 0.30，平均 0.45 根/弧）≈ 3.4k/实体、全场 ≈ 11k。
 * 作为对照：编号 1 的单条链式闪电 ≈1500 顶点。真正的数字每 1.5 秒由 {@code [ShockArc] 绘制: … 顶点=V}
 * 打在日志里，不必估。</p>
 *
 * <h2>渲染走哪条路（一个字节的新着色器都没有）</h2>
 * <p>复用编号 1 已验证的那条：{@code LightningRenderer} 的 pass（{@code AFTER_PARTICLES}，
 * {@code RenderStateSnapshot} 整份快照/还原）+ {@link ArcGeometry} 折线 + {@code
 * rendertype_lightning_arc}（JSON 有 {@code "blend"} 节点、加法混合、
 * {@code ColorModulator} 不被动过）。本类只提供"往同一个 BufferBuilder 里再写一批折线"。
 * <b>不再有任何"闪电类 RenderType 走模型几何镜像"的东西</b>（那两层的黑屏档案见
 * {@code ModelMirrorLayers}），也不再新增着色器 ⇒ 2/3 号那类黑屏风险从结构上不存在。</p>
 *
 * <h2>兜底（用户明确要求：不要包围盒）</h2>
 * <p>锚点表带 {@code fresh} 标记：由 {@link #beginFrame}（实体渲染时）置位、
 * 由 {@link #endFrame}（每帧渲染收尾，{@code LightningRenderer} 里无条件调用）清除。
 * 某帧实体没被渲染（视锥剔除 / 第一人称的摄像机实体）⇒ 该实体本帧<b>一条弧都不画</b>，
 * 绝不退回包围盒估位置。</p>
 *
 * <p><b>线程</b>：{@link #beginFrame}/{@link #emit}/{@link #endFrame} 在渲染线程， * {@link #tick} 在客户端 tick（原版两者是同一个主线程），故这几条路径本身不需要锁；
 * 但 {@code /yiz fxtoggle 4} 走的是<b>服务端线程</b>的指令回调（整合服务器与客户端同 JVM，
 * 见 {@code YizFxToggleCommand} 的类注释），它会在渲染线程正遍历 {@link #TRACKS} 时调用
 * {@link #clear()} ⇒ 与 {@code ShockEffects} 同一套做法：<b>对这一张表的所有访问都加
 * {@code synchronized (TRACKS)}</b>（无竞争时开销可忽略，指令是低频操作）。
 * 锁序固定为 {@code TRACKS → ACTIVE}（emit 里先持 TRACKS 再查 ShockEffects），
 * 反向没有路径 ⇒ 不会死锁。</p>
 */
public final class ShockArcs {

    private static final Logger LOGGER = LoggerFactory.getLogger("YizShockArc");

    // ══════════════════════════ 参数表（调观感只改这里） ══════════════════════════

    // ── 数量（按屏幕覆盖率自适应）──
    /**
     * 单实体弧数上限。
     *
     * <p>2026-10-04 第二轮（用户："还要更多的特效"）24 → <b>44</b>：密度翻近一倍，
     * 近距离大目标（监守者 ≈2.3 格半径）在 8 格内都吃满上限。顶点预算见
     * {@link #ARC_TOTAL_MAX} 上方的整段说明 —— 单实体 44 条 × 72 顶点 ≈ 3.2k 顶点/帧，
     * 与编号 1 的单条链式闪电（≈1500）同量级，可接受。</p>
     */
    private static final int ARC_MAX = 44;
    /** 单实体弧数下限：再远也要有"零星几道"，否则远处目标看着完全没电。 */
    private static final int ARC_MIN = 3;
    /**
     * 目标条数 = 该系数 × (模型半径 / 到相机距离)。
     * 实测锚点云的半径：玩家 ≈0.95 格（头顶到脚底的一半多一点）⇒ 4 格外目标 ≈38 条、
     * 8 格外 ≈19 条、20 格外 ≈8 条、40 格外 ≈4 条；换成体型更大的目标（监守者 ≈2.2 格），
     * 分子变大 ⇒ 7 格内就吃满 {@link #ARC_MAX}。远处仍然自动降量（这一条<b>没有</b>被改掉，
     * 只是系数从 96 提到 160：所有距离同比例变多，距离衰减关系不变）。
     */
    private static final float APPARENT_GAIN = 160f;
    /**
     * 每 tick 最多补生几条（补生速率上限）。它与寿命共同决定稳态条数：
     * 稳态 ≈ 本值 × 20 × 平均寿命 = 7 × 20 × 0.325s ≈ 45 条 ⇒ 近距离时真正生效的上限仍是
     * {@link #ARC_MAX}（44 条）；远处由屏幕覆盖率把目标条数压下去、根本补不到这个速率。
     */
    private static final int SPAWN_PER_TICK = 7;
    /**
     * <b>全场弧数总预算（跨所有感电实体）</b>：目标条数之和超过它时，所有实体按同一比例缩水。
     *
     * <p>为什么必须有这一条：{@link #ARC_MAX} 是<b>每实体</b>上限，而现场日志里一次出现过
     * 8 个监守者同时感电（latest.log 03:10:19 的 warden#1/#5/#7/…/#19）——
     * 8 × 44 条 × 72 顶点 ≈ 25k 顶点/帧就压到预算线上了。有了总预算，
     * "多目标"这种极端情况自动降级为"每个目标少几条"，而不是线性堆到爆。</p>
     */
    private static final int ARC_TOTAL_MAX = 140;
    /**
     * <b>全场每帧顶点硬上限（第三轮新增的兜底）</b>：{@link #ARC_MAX} 与 {@link #ARC_TOTAL_MAX}
     * 限的是<b>条数</b>，只要参数被改坏（例如把 {@code FILAMENT_MAX} 调大、或把 {@code SEGMENTS}
     * 调大）顶点数仍可能成倍上涨。这一条限的是<b>顶点</b>本身，是本 pass 的最后一道保险：
     * 累计到上限就停止写顶点（这一帧剩下的弧不画，下一帧照常），并且限流打一条 warn。</p>
     *
     * <p>取值依据：{@link #ARC_TOTAL_MAX}(140) × 最坏单弧
     * （主干 {@code 12×SEGMENTS(6)}=72 + {@code FILAMENT_MAX(2)×16}=32 ⇒ 104）= 14560；
     * 20000 约留 37% 余量，正常路径永远碰不到（现场日志里 9 只监守者同时感电也只有 1.6~2.0k/实体），
     * 一旦碰到就说明有东西算错了 —— 正是"宁可少画，也不要因为几何爆掉而糊满屏"。</p>
     */
    private static final int FRAME_VERT_MAX = 20000;

    // ── 偏移 / 长度（格）──
    /** 从体表往外推的最小距离：太贴会和本体/盔甲打架。 */
    private static final float OFF_MIN = 0.05f;
    /** 往外推的最大距离：这一圈厚度就是"弧飘在体外"的体积感来源（0.35 → 0.50：弧层更厚、空间感更强）。 */
    private static final float OFF_MAX = 0.50f;
    /** 偏移方向的随机抖动（每轴 ±该值）：免得所有弧都规规矩矩沿法线排（0.06 → 0.10）。 */
    private static final float JITTER = 0.10f;
    /** 弧两端的最小间距（格）：太短的弧看不出来，推开到这个长度。 */
    private static final float SEG_MIN = 0.14f;
    /** 弧两端的最大间距（格）：一条"短弧"的尺度上限（也避免横穿身体的长线）。 */
    private static final float SEG_MAX = 0.60f;
    /** 挑第二个锚点时随机试探的次数：从这几次里挑离第一个最近的 ⇒ 弧是短的、局部的。 */
    private static final int NEIGHBOR_TRIES = 6;

    // ── 几何形态 ──
    /** 单条弧的锯齿折线段数（5 → 6：弧更长、折点更多，"噼啪"的细节更足）。 */
    private static final int SEGMENTS = 6;
    /** 单条弧最多挂几根细丝（{@code ArcGeometry.emitShortArc} 的分支）。 */
    private static final int FILAMENT_MAX = 2;
    /** 挂细丝的概率（低概率 ⇒ "电弧丛"；每条弧都挂就成了毛球）。 */
    private static final float FILAMENT_CHANCE = 0.30f;
    /**
     * 主干半宽（格）：2.8 cm ⇒ 4 格外看过去约 8~10 像素、10 格外约 3~4 像素，
     * 比编号 1 的链式电弧（0.05）细一档 —— 弧本身只有 0.14~0.60 格长，太粗就成了"光棒"。
     * 实际画出来的半宽还会乘一条随亮度变化的系数（见 {@link #emit}）。
     */
    private static final float HALF_WIDTH = 0.028f;

    // ── 生灭 ──
    /** 寿命下限（秒）。 */
    private static final float LIFE_MIN = 0.15f;
    /** 寿命上限（秒）。 */
    private static final float LIFE_MAX = 0.50f;
    /** 每 tick 推进的寿命（秒）：与编号 1 的 {@code LightningRenderer#tick} 同一节拍（20 Hz）。 */
    private static final float TICK_DT = 0.05f;
    /** 逐条随机亮度下限（0.60 → 0.55：暗弧更暗，层次拉开）。 */
    private static final float BRIGHT_MIN = 0.55f;
    /** 长尾的分界亮度：{@link #BRIGHT_TAIL_FRAC} 之外的弧落在 [MIN, MID]，之内的落在 [MID, MAX]。 */
    private static final float BRIGHT_MID = 1.05f;
    /** 逐条随机亮度上限（1.35 → 1.60：少数弧明显更亮 ⇒ "噼啪闪烁"的层次）。 */
    private static final float BRIGHT_MAX = 1.60f;
    /**
     * 落在长尾（[MID, MAX]）的弧的比例。0.20 = 每 5 条里有 1 条是亮弧 ——
     * 均匀分布时"亮度"是一团均值噪声，看不出层次；两段分布才有"某几条在炸"的观感。
     */
    private static final float BRIGHT_TAIL_FRAC = 0.20f;
    /** 生命周期里"淡入"占的比例。 */
    private static final float FADE_IN_FRAC = 0.18f;
    /** 生命周期里"淡出"占的比例。 */
    private static final float FADE_OUT_FRAC = 0.35f;

    // ── 自检日志 ──
    /** 每实体每 1.5 秒最多一条绘制日志（不刷屏，但足够判定"锚点/弧/亮度"三者是否正常）。 */
    private static final long DETAIL_LOG_INTERVAL_MS = 1500L;

    // ══════════════════════════ 状态 ══════════════════════════

    /**
     * 每个"正在感电且被渲染过"的实体一条 Track（引用比较；存活数 = 感电实体数，个位数）。
     *
     * <p>所有访问都在 {@code synchronized (TRACKS)} 里（含 {@link #clear()}，
     * 它可能来自服务端线程的 {@code /yiz fxtoggle 4}）—— 理由见类注释的"线程"一节。</p>
     */
    private static final Map<Entity, Track> TRACKS = new IdentityHashMap<>();

    private ShockArcs() {}

    // ══════════════════════════ 顶点 → 锚点（实体渲染期） ══════════════════════════

    /**
     * 实体渲染前调用（由 {@code ModelMirrorBufferSource#begin} 转发）：本帧这个实体要不要收锚点。
     *
     * @return 非 null = 收（返回那个"只看不写"的顶点接收器工厂，会被挂进多路复用器）；
     *         null = 本帧不收（开关 4 关着 / 该实体没有感电状态）
     */
    public static ShockAnchors.Tap beginFrame(Entity entity) {
        if (!FxDebugSwitches.surfaceArcs()) return null;
        synchronized (TRACKS) {
            Track t = TRACKS.get(entity);
            if (t == null) {
                // 只有真的在感电的实体才建表：没感电的实体一个顶点都不会被额外收走
                if (ShockEffects.shockOf(entity) == null) return null;
                t = new Track(entity);
                TRACKS.put(entity, t);
                LOGGER.info("[ShockArc] 锚点采集开始: entity={} 容量={}（锚点 = 模型自己写出的顶点，"
                                + "视图空间；弧的端点全部取自这张表，写入时用单位阵）"
                                + "；本轮起锚点按「该层贴图 alpha」过滤：面 → UV 矩形 2×2~4×4 网格 → 纹素 alpha≥0.1 "
                                + "→ 四角双线性插值出位置，"
                                + "贴图没画到的地方（例如监守者耳朵那块平板的空白区）一个锚点都不产生",
                        MirrorDiag.entity(entity), ShockAnchors.CAPACITY);
            }
            t.anchors.beginFrame();
            t.fresh = true;
            return t.tap;
        }
    }

    /**
     * 每帧渲染收尾调用（{@code LightningRenderer#onRenderLevelStage} 里无条件执行）：
     * 清掉"本帧被渲染过"的标记。
     *
     * <p>这一句是"实体没被渲染就不画弧"的<b>唯一</b>依据：实体渲染发生在
     * {@code AFTER_PARTICLES} 之前，所以"这一帧有没有被重新采过"就等于
     * {@code fresh} 是不是还在。</p>
     */
    public static void endFrame() {
        synchronized (TRACKS) {
            if (TRACKS.isEmpty()) return;
            for (Track t : TRACKS.values()) {
                // ── 本轮新增的诊断出口：本帧这个实体被渲染过、也确实攒出了面，却一个可用锚点都没有 ──
                // 这种情况会"静默地不出弧"（没有弧 ⇒ emit 里那条 1.5 秒的绘制日志也不会打），
                // 所以要在这里把原因写出来，免得用户只看到"没效果"却没有任何线索。
                // 判据用 fresh（本帧确实被渲染过）+ statFaces>0（确实走到过面判定）+ count<2（不足以生成弧）。
                if (t.fresh && !t.warnedNoAnchor && t.anchors.statFaces() > 0 && t.anchors.count() < 2) {
                    t.warnedNoAnchor = true;
                    LOGGER.info("[ShockArc] 该实体本帧攒出了面却没有任何可用锚点（锚点={} < 2）⇒ 本帧不会产生弧。"
                                    + "过滤统计: entity={} 面={} 有画面={} 空白面={} 未过滤面={} 候选={} 原因={} —— "
                                    + "「有画面=0」= 所有面在该层贴图上都是透明的（这张贴图几乎没画东西，或是非资源包贴图）；"
                                    + "「候选=1」= 只有一个采样点通过判据。这一条每个实体只打一次，用于区分"
                                    + "“过滤把锚点全滤掉了”与“模型压根没渲染/没感电”",
                            t.anchors.count(), MirrorDiag.entity(t.entity), t.anchors.statFaces(),
                            t.anchors.statPaintedFaces(), t.anchors.statBlankFaces(),
                            t.anchors.statUnfilteredFaces(), t.anchors.statOffers(),
                            ShockAnchors.reasonText(t.anchors.filterReasons()));
                }
                t.fresh = false;
            }
        }
    }

    // ══════════════════════════ tick：生灭 ══════════════════════════

    /** 客户端每 tick（{@code ShaderRenderRegistrar}）：推进寿命、补生新弧、回收状态没了的实体。 */
    public static void tick() {
        synchronized (TRACKS) {
            if (TRACKS.isEmpty()) return;
            // ── 第一遍：推进寿命 + 回收（感电没了/实体没了 → 连弧一起收掉，不留残影，也不退回包围盒）──
            Iterator<Map.Entry<Entity, Track>> it = TRACKS.entrySet().iterator();
            while (it.hasNext()) {
                Track t = it.next().getValue();
                if (t.entity.isRemoved() || !t.entity.isAlive() || ShockEffects.shockOf(t.entity) == null) {
                    it.remove();
                    continue;
                }
                for (int i = t.arcs.size() - 1; i >= 0; i--) {
                    Arc a = t.arcs.get(i);
                    a.life -= TICK_DT;
                    if (a.life <= 0f) t.arcs.remove(i);
                }
            }
            if (TRACKS.isEmpty()) return;

            // ── 第二遍：算目标条数，并施加"全场总预算"（多目标时按比例缩水，见 ARC_TOTAL_MAX）──
            int sum = 0;
            for (Track t : TRACKS.values()) {
                t.target = targetCount(t);
                sum += t.target;
            }
            float scale = sum > ARC_TOTAL_MAX ? ARC_TOTAL_MAX / (float) sum : 1f;

            // ── 第三遍：补生（每 tick 最多 SPAWN_PER_TICK 条，避免"一帧内长出一整丛"）──
            for (Track t : TRACKS.values()) {
                int raw = t.target;      // 第二遍写进来的"屏幕覆盖率目标"（0 = 锚点还不够两个）
                int target = raw == 0 ? 0 : Math.max(ARC_MIN, Math.round(raw * scale));
                t.target = target;       // 诊断日志读的是这个"预算缩放后"的值，与画面对得上
                int n = t.anchors.count();
                if (n >= 2 && t.arcs.size() < target) {
                    for (int k = 0; k < SPAWN_PER_TICK && t.arcs.size() < target; k++) {
                        t.arcs.add(spawn(t, n));
                    }
                }
            }
        }
    }

    /**
     * 逐条随机亮度：<b>两段（长尾）分布</b>，不是均匀分布。
     *
     * <p>{@code u ∈ [0,1)}；前 {@code 1-BRIGHT_TAIL_FRAC} 的弧落在 {@code [BRIGHT_MIN, BRIGHT_MID]}
     * （常规弧），其余落在 {@code [BRIGHT_MID, BRIGHT_MAX]}（亮弧）。
     * 均匀分布时"亮度"只是一团均值噪声、看不出层次；两段分布才有"大部分弧在嘶嘶响、
     * 少数几条在炸"的噼啪感 —— 而且亮弧还会因为 {@link #emit} 里
     * {@code halfWidth = HALF_WIDTH * (0.60 + 0.40 * lum)} 而更粗，层次是双重叠加的。</p>
     */
    private static float sampleBright(ThreadLocalRandom rnd) {
        float u = rnd.nextFloat();
        if (u < 1f - BRIGHT_TAIL_FRAC) {
            return BRIGHT_MIN + (BRIGHT_MID - BRIGHT_MIN) * (u / (1f - BRIGHT_TAIL_FRAC));
        }
        return BRIGHT_MID + (BRIGHT_MAX - BRIGHT_MID) * ((u - (1f - BRIGHT_TAIL_FRAC)) / BRIGHT_TAIL_FRAC);
    }

    /**
     * 逐条随机"细丝根数"：以 {@link #FILAMENT_CHANCE} 的概率挂 1 根，其中一半概率再挂第 2 根。
     * ⇒ 期望 {@code 0.30 × 1.5 = 0.45} 根/弧，平均每弧多 7 个顶点（{@code 16 顶点/根}）。
     */
    private static int sampleFilaments(ThreadLocalRandom rnd) {
        if (rnd.nextFloat() >= FILAMENT_CHANCE) return 0;
        return rnd.nextBoolean() && FILAMENT_MAX >= 2 ? 2 : 1;
    }

    /** 目标条数：按"屏幕覆盖率"（模型半径 / 到相机距离）自适应，夹在 {@link #ARC_MIN}~{@link #ARC_MAX}。 */
    private static int targetCount(Track t) {
        if (t.anchors.count() < 2) return 0;
        t.anchors.ensureSummary();
        float dist = Math.max(1.0f, t.anchors.centroidDistance());
        float radius = Math.max(0.15f, t.anchors.radius());
        int target = Math.round(APPARENT_GAIN * radius / dist);
        return Math.max(ARC_MIN, Math.min(ARC_MAX, target));
    }

    /**
     * 生成一条弧：两个锚点下标 + 两段相对偏移 + 随机种子/亮度/寿命。
     *
     * <p><b>只在生成时算一次几何参数</b>：偏移是与锚点同一个空间（视图空间）里的常量向量，
     * （该空间与世界只差一个平移，方向不变），所以逐帧渲染时只要"本帧锚点 + 这个偏移"
     * 就能复现出跟着模型走的弧 —— 不需要每帧重新随机，也不会每帧抖动到别处。</p>
     */
    private static Arc spawn(Track t, int n) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        ShockAnchors an = t.anchors;
        an.ensureSummary();

        int ia = rnd.nextInt(n);
        int ib = nearestAnchor(an, ia, n, rnd);
        Arc a = new Arc();
        a.ia = ia;
        a.ib = ib;
        a.seed = rnd.nextInt();
        a.maxLife = LIFE_MIN + rnd.nextFloat() * (LIFE_MAX - LIFE_MIN);
        a.life = a.maxLife;
        a.bright = sampleBright(rnd);
        a.filaments = sampleFilaments(rnd);

        // 颜色：在"外晕青蓝（EDGE）"与"弧心近白（CORE）"之间随机取一档 ⇒ 有冷有亮，不是同一根线
        float temp = 0.55f + 0.45f * rnd.nextFloat();
        a.r = lerp(ShockEffects.EDGE_R, ShockEffects.CORE_R, temp);
        a.g = lerp(ShockEffects.EDGE_G, ShockEffects.CORE_G, temp);
        a.b = lerp(ShockEffects.EDGE_B, ShockEffects.CORE_B, temp);

        // 偏移：沿"该锚点相对模型质心的径向外"推 OFF 格 + 每轴随机抖动 ⇒ 弧悬在体外那一圈空间里
        setOffset(an, ia, 0, a, rnd);
        setOffset(an, ib, 1, a, rnd);

        clampLength(an, a, rnd);
        return a;
    }

    /**
     * 算一个锚点的偏移向量，直接写进弧的字段（{@code which} 0 = 起点、1 = 终点）。
     *
     * <p>方向 = 该锚点相对模型质心的径向（单位化）× 随机外移距离 {@link #OFF_MIN}~{@link #OFF_MAX}，
     * 再叠每轴 ±{@link #JITTER} 的抖动；锚点正好落在质心上（退化）时改用随机方向，
     * 绝不让 NaN 传下去。整段零分配（结果直接落进弧的字段）。</p>
     */
    private static void setOffset(ShockAnchors an, int i, int which, Arc a, ThreadLocalRandom rnd) {
        float dx = an.x(i) - an.centroidX();
        float dy = an.y(i) - an.centroidY();
        float dz = an.z(i) - an.centroidZ();
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        float dist = OFF_MIN + rnd.nextFloat() * (OFF_MAX - OFF_MIN);
        float ox, oy, oz;
        if (len < 1.0e-4f) {
            // 退化：随机方向（与上面 clampLength 的退化分支同一套球面均匀取法）
            double ang = rnd.nextDouble() * Math.PI * 2.0;
            float uy = rnd.nextFloat() * 2f - 1f;
            float sr = (float) Math.sqrt(Math.max(0f, 1f - uy * uy));
            ox = (float) (Math.cos(ang) * sr) * dist;
            oy = uy * dist;
            oz = (float) (Math.sin(ang) * sr) * dist;
        } else {
            ox = dx / len * dist;
            oy = dy / len * dist;
            oz = dz / len * dist;
        }
        ox += (rnd.nextFloat() - 0.5f) * 2f * JITTER;
        oy += (rnd.nextFloat() - 0.5f) * 2f * JITTER;
        oz += (rnd.nextFloat() - 0.5f) * 2f * JITTER;
        if (which == 0) {
            a.oax = ox;
            a.oay = oy;
            a.oaz = oz;
        } else {
            a.obx = ox;
            a.oby = oy;
            a.obz = oz;
        }
    }

    /**
     * 第二个锚点在 {@link #NEIGHBOR_TRIES} 次随机试探里挑"离第一个最近"的那个 ⇒ 弧是短的、局部的。
     * 只做 6 次试探（O(6)），不做全表最近邻 —— 全表也才 160 个点，但没必要每 tick 每弧都扫一遍。
     */
    private static int nearestAnchor(ShockAnchors an, int ia, int n, ThreadLocalRandom rnd) {
        int best = ia;
        float bestD = Float.MAX_VALUE;
        for (int k = 0; k <= NEIGHBOR_TRIES; k++) {
            int c = rnd.nextInt(n);
            if (c == ia) continue;
            float dx = an.x(c) - an.x(ia);
            float dy = an.y(c) - an.y(ia);
            float dz = an.z(c) - an.z(ia);
            float d = dx * dx + dy * dy + dz * dz;
            if (d < bestD) {
                bestD = d;
                best = c;
            }
        }
        if (best == ia) {
            // 表里只有一个点（或全部试探都撞上自己）：随便取下一个，至少不会两端同点
            best = (ia + 1) % n;
        }
        return best;
    }

    /** 把两端距离夹进 {@link #SEG_MIN}~{@link #SEG_MAX}（通过改第二个端点的偏移实现）。 */
    private static void clampLength(ShockAnchors an, Arc a, ThreadLocalRandom rnd) {
        float p0x = an.x(a.ia) + a.oax, p0y = an.y(a.ia) + a.oay, p0z = an.z(a.ia) + a.oaz;
        float ex = an.x(a.ib) + a.obx - p0x;
        float ey = an.y(a.ib) + a.oby - p0y;
        float ez = an.z(a.ib) + a.obz - p0z;
        float d = (float) Math.sqrt(ex * ex + ey * ey + ez * ez);
        if (d > SEG_MAX) {
            float s = (d - SEG_MAX) / d;         // 把第二个端点沿连线往回收
            a.obx -= ex * s;
            a.oby -= ey * s;
            a.obz -= ez * s;
        } else if (d < SEG_MIN) {
            if (d < 1.0e-4f) {
                // 两端重合（同一点上的两个锚点）：随机推一个方向出去
                double ang = rnd.nextDouble() * Math.PI * 2.0;
                float uy = rnd.nextFloat() * 2f - 1f;
                float sr = (float) Math.sqrt(Math.max(0f, 1f - uy * uy));
                a.obx += (float) (Math.cos(ang) * sr) * SEG_MIN;
                a.oby += uy * SEG_MIN;
                a.obz += (float) (Math.sin(ang) * sr) * SEG_MIN;
            } else {
                float s = (SEG_MIN - d) / d;     // 沿连线把第二个端点推开
                a.obx += ex * s;
                a.oby += ey * s;
                a.obz += ez * s;
            }
        }
    }

    // ══════════════════════════ 渲染（与实体本体同一个坐标空间） ══════════════════════════

    /**
     * <b>传给顶点写入的单位阵 —— 这不是"偷懒"，是坐标系决定的（务必看清）</b>。
     *
     * <p>本 pass 里有<b>两套</b>坐标并存：</p>
     * <ul>
     *   <li><b>编号 1（链式电弧）</b>：端点由实体世界位置算出，走的是"相机相对世界坐标"，
     *       必须用 {@code mat = event.getPoseStack().last().pose()}（= 视图矩阵 V，只含视图旋转）
     *       把它转成视图空间；</li>
     *   <li><b>编号 4（围弧）</b>：端点是<b>锚点 + 偏移</b>，而锚点取自模型顶点 ——
     *       模型顶点<b>已经</b>是视图空间（实体渲染时 PoseStack 里已经乘过视图旋转 + 相机相对平移，
     *       见 {@code rendertype_flame_surface.vsh} 的坐标系说明，以及"实体渲染期间 ModelViewMat 是单位阵"
     *       这一事实：若视图旋转不在顶点里，模型根本不可能正确地朝向屏幕）。
     *       ⇒ 再乘一次 {@code mat} 就是<b>把视图旋转乘两遍</b>，弧会跟着镜头转而不是跟着身体走。</li>
     * </ul>
     * <p>所以围弧的顶点用<b>单位阵</b>写入：{@code gl_Position = ProjMat * ModelViewMat(单位阵) * Position}
     * —— 与实体本体、与贴模型火焰逐字同一条路（那两者也没有任何额外的矩阵）。</p>
     */
    private static final Matrix4f IDENTITY = new Matrix4f();

    /**
     * 把当前所有存活的弧写进顶点缓冲（由 {@code LightningRenderer} 在
     * {@code AFTER_PARTICLES} 的同一个 BufferBuilder 里调用，与编号 1 共用着色器与状态）。
     *
     * @return 是否真的写了顶点（false = 本帧没有可画的弧，调用方据此跳过 draw）
     */
    public static boolean emit(VertexConsumer vc) {
        synchronized (TRACKS) {
            if (TRACKS.isEmpty()) return false;
            float time = ShaderTimeUtil.now();
            boolean wrote = false;
            // 本帧全场已写顶点数（跨实体累计）。第三轮兜底：见 FRAME_VERT_MAX 的说明。
            int frameVerts = 0;
            boolean capped = false;
            int nanSkipped = 0;
            for (Track t : TRACKS.values()) {
                // 本帧实体没被渲染 ⇒ 锚点是旧的 ⇒ 一条都不画（绝不退回包围盒估位置）
                if (!t.fresh || t.arcs.isEmpty()) continue;
                ShockEffects.SurfaceShock state = ShockEffects.shockOf(t.entity);
                if (state == null) continue;
                float strength = state.strength(t.entity.tickCount);
                if (strength <= 0.001f) continue;
                int n = t.anchors.count();
                if (n == 0) continue;
                int verts = 0;

                for (int i = 0; i < t.arcs.size(); i++) {
                    Arc a = t.arcs.get(i);
                    float fade = fade(a);
                    if (fade <= 0.002f) continue;
                    // 下标对当前表长取模：表长因模型变化而改变时，弧仍指向合法的锚点（不会越界、不会消失）
                    int ia = a.ia % n;
                    int ib = a.ib % n;
                    Vec3 from = new Vec3(t.anchors.x(ia) + a.oax, t.anchors.y(ia) + a.oay, t.anchors.z(ia) + a.oaz);
                    Vec3 to = new Vec3(t.anchors.x(ib) + a.obx, t.anchors.y(ib) + a.oby, t.anchors.z(ib) + a.obz);
                    // ── 兜底 1（第三轮新增）：端点必须是有限值 ──
                    // 锚点来自"模型自己写出的顶点"，别的模组/骨骼动画把某个顶点写成 NaN/Inf 是可能的
                    // （也可以是实体被瞬移到极端坐标）。NaN 顶点在部分驱动上会退化成"覆盖小半个屏幕"
                    // 的三角形（糊屏），所以这里直接跳过这条弧，而不是把 NaN 交给 GPU。
                    if (!finite(from) || !finite(to)) { nanSkipped++; continue; }
                    // ── 兜底 2（第三轮新增）：全场每帧顶点硬上限 ──
                    int arcVerts = 12 * SEGMENTS + a.filaments * ArcGeometry.SHORT_ARC_FILAMENT_SEGMENTS * 4;
                    if (frameVerts + arcVerts > FRAME_VERT_MAX) { capped = true; break; }
                    // ── 亮度：写进 RGB 与线宽，<b>不能只写顶点 alpha</b> ──
                    // rendertype_lightning_arc 的片元着色器只读 vColor.rgb，vColor.a 完全不参与
                    // （它自己的 alpha 由 core/flick/近距淡出算），所以"逐条随机亮度 + 生灭淡入淡出"
                    // 必须落在颜色与线宽上：暗/将死的弧更细更暗，亮/正盛的弧更粗更亮。
                    // 这一条对观感很关键 —— 不这么做，所有弧会一样亮、并且"啪"地消失。
                    float lum = Math.min(1.0f, fade * a.bright * strength);
                    float halfWidth = HALF_WIDTH * (0.60f + 0.40f * lum);
                    ArcGeometry.emitShortArc(vc, IDENTITY, from, to, a.seed, halfWidth,
                            a.r * lum, a.g * lum, a.b * lum, lum, time, SEGMENTS, a.filaments);
                    // 顶点计数（诊断用，也是"性能护栏"的现场证据）：
                    // 主干 3 带 × SEGMENTS 段 × 4 顶点；每根细丝 1 带 × 4 段 × 4 顶点。
                    verts += arcVerts;
                    frameVerts += arcVerts;
                    wrote = true;
                }
                logDraw(t, strength, verts);
            }
            // 两条兜底的现场证据（限流：整个会话各一条；正常路径一条都不打）
            if (nanSkipped > 0 && MirrorDiag.first("shockarc:nan")) {
                LOGGER.warn("[ShockArc] 本帧有 {} 条弧的端点不是有限值（NaN/Inf，来自模型顶点锚点），已跳过不画 "
                                + "—— 这是【几何糊满屏】那条路的兜底：NaN 顶点在部分驱动上会被扩成覆盖大片屏幕的"
                                + "退化三角形。若反复出现，请查是哪个模组/哪次瞬移把模型顶点写成了非有限值",
                        nanSkipped);
            }
            if (capped && MirrorDiag.first("shockarc:vertcap")) {
                LOGGER.warn("[ShockArc] 本帧顶点数达到硬上限 {}（已写 {}），剩余弧本帧不再画 —— "
                                + "正常路径碰不到这个上限（140 条 × 最坏 104 顶点 = 14560）；"
                                + "碰到就说明 ARC_MAX/ARC_TOTAL_MAX/SEGMENTS/FILAMENT_MAX 有哪一项被改坏了",
                        FRAME_VERT_MAX, frameVerts);
            }
            return wrote;
        }
    }

    /** 三个分量都是有限值（不是 NaN / ±Inf）。兜底用，见 {@link #emit} 的"兜底 1"。 */
    private static boolean finite(Vec3 v) {
        return Double.isFinite(v.x) && Double.isFinite(v.y) && Double.isFinite(v.z);
    }

    /**
     * 弧的寿命曲线：前 {@link #FADE_IN_FRAC} 淡入、后 {@link #FADE_OUT_FRAC} 淡出
     * （生灭不"啪"地出现/消失）。
     *
     * <p>起点给 0.35 而不是 0：弧是在 tick 里生成的，而"刚生成"的那几帧 {@code life == maxLife}
     * （寿命要到下一次 tick 才推进）—— 若淡入从 0 起算，最短的弧（3 tick）会有 50ms 完全不可见，
     * 等于白白浪费 {@link #SPAWN_PER_TICK} 的补生配额。给个 0.35 的初值就是"啪"地闪出来、
     * 再在一两帧内窜到最亮，正好是电弧该有的观感。</p>
     */
    private static float fade(Arc a) {
        float u = a.maxLife <= 0f ? 0f : a.life / a.maxLife;   // 1 → 0
        float in = Math.min(1.0f, 0.35f + (1.0f - u) / FADE_IN_FRAC);
        float out = Math.min(1.0f, u / FADE_OUT_FRAC);
        return Math.max(0.0f, Math.min(in, out));
    }

    // ══════════════════════════ 生命周期 / 诊断 ══════════════════════════

    /** 当前是否有任何存活的弧（{@code LightningRenderer} 据此决定要不要进绘制 pass）。 */
    public static boolean hasLiveArcs() {
        synchronized (TRACKS) {
            for (Track t : TRACKS.values()) {
                if (!t.arcs.isEmpty()) return true;
            }
            return false;
        }
    }

    /** 清空全部（{@code /yiz fxtoggle 4} 关掉 / {@code all off}）：状态表与弧一起没了 ⇒ 立刻不画。 */
    public static void clear() {
        // 注意本方法可能来自服务端线程（指令回调），必须与渲染线程互斥 —— 见类注释的"线程"
        synchronized (TRACKS) {
            TRACKS.clear();
        }
    }

    /**
     * 一行诊断摘要（{@code /yiz fxtoggle status} 用）：例如 {@code 实体=1 弧=19}。
     * 用户实测时"敲 status 就知道现在到底有没有弧在画"，比翻日志快。
     * （弧=0 的三种正常情形：没有实体在感电 / 开关 4 关着 / 感电实体本帧没被渲染。）
     */
    public static String debugSummary() {
        synchronized (TRACKS) {
            int arcs = 0;
            for (Track t : TRACKS.values()) arcs += t.arcs.size();
            return "实体=" + TRACKS.size() + " 弧=" + arcs;
        }
    }

    /**
     * 自检日志（每实体每 1.5 秒最多一条）：一次游戏就能判定
     * "锚点有没有收到（锚点=N）""弧在不在（弧=M/目标=K）""亮不亮（强度=g）""顶点花多少（顶点=V）"
     * "贴图过滤有没有生效（面=/有画面=/空白面=/候选=/未过滤面=/过滤=）"。
     *
     * <p>{@code 顶点=} 是这一帧该实体真正写进缓冲的顶点数（主干 12×{@link #SEGMENTS} + 细丝 16/根），
     * 也就是<b>性能护栏的现场证据</b>：单实体上限 = {@link #ARC_MAX} × (12×{@link #SEGMENTS}
     * + {@link #FILAMENT_MAX}×16) = 44 × 104 = 4576 顶点/帧；全场（{@link #ARC_TOTAL_MAX}=140 条）
     * 最坏 ≈ 14.6k 顶点/帧，典型（细丝概率 0.30）≈ 3.4k/实体、全场 ≈ 11k ——
     * 与编号 1 的单条链式闪电（≈1500 顶点）相比仍是同一量级。</p>
     *
     * <p><b>锚点过滤那一组数字怎么读</b>（本轮新增，统计口径见 {@link ShockAnchors}）：
     * {@code 面=} 本帧该实体所有层加起来攒出的 QUADS 面数；{@code 有画面=} 其中至少有一个采样点
     * 落在不透明纹素上的面数（<b>只有这些面会产生锚点</b>）；{@code 空白面=} 一个通过点都没有的面数
     * （监守者耳朵那块 16×16 平板的后背、四条棱都在这一类）；{@code 候选=} 通过判据的采样点总数
     * （抽稀之前，最终 {@code 锚点=} 恒 ≤{@link ShockAnchors#CAPACITY}）；{@code 未过滤面=} 因
     * "UV 越界/被斜切/缺 UV"而退回逐顶点的面数；{@code 过滤=} 该实体本帧的层模式
     * （全过滤 / 有层退回并给出原因）。判据是否生效，看这四个数一次就能判定：
     * 耳朵那种"大片几何 + 小片贴图"的部件应当表现为<b>有画面数远小于面数</b>。</p>
     */
    private static void logDraw(Track t, float strength, int verts) {
        long now = System.currentTimeMillis();
        if (now < t.nextLogMs) return;
        t.nextLogMs = now + DETAIL_LOG_INTERVAL_MS;
        ShockAnchors an = t.anchors;
        int reasons = an.filterReasons();
        String filter = reasons == 0
                ? "过滤=按贴图alpha(逐层,全部生效)"
                : "过滤=有层/面退回(" + ShockAnchors.reasonText(reasons) + ")";
        LOGGER.info("[ShockArc] 绘制: entity={} 锚点={} 弧={}/目标={} 顶点={} 强度={} 半径={}格 到相机={}格 "
                        + "‖ 面={} 有画面={} 空白面={} 未过滤面={} 候选={} {} "
                        + "‖ 偏移={}~{}格 长度={}~{}格 寿命={}~{}s 段数={} 细丝≤{}根({}概率) 亮度={}~{}({}%走长尾) 上限={}条/实体 {}条/全场",
                MirrorDiag.entity(t.entity), an.count(), t.arcs.size(), t.target, verts,
                fmt(strength), fmt(an.radius()), fmt(an.centroidDistance()),
                an.statFaces(), an.statPaintedFaces(), an.statBlankFaces(), an.statUnfilteredFaces(),
                an.statOffers(), filter,
                fmt(OFF_MIN), fmt(OFF_MAX), fmt(SEG_MIN), fmt(SEG_MAX),
                fmt(LIFE_MIN), fmt(LIFE_MAX), SEGMENTS, FILAMENT_MAX,
                String.format(java.util.Locale.ROOT, "%.0f%%", FILAMENT_CHANCE * 100f),
                fmt(BRIGHT_MIN), fmt(BRIGHT_MAX),
                String.format(java.util.Locale.ROOT, "%.0f", BRIGHT_TAIL_FRAC * 100f),
                ARC_MAX, ARC_TOTAL_MAX);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static String fmt(float v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }

    // ══════════════════════════ 数据结构 ══════════════════════════

    /** 一条实体的围弧状态：锚点表 + 存活弧 + "本帧被渲染过"标记。 */
    private static final class Track {

        final Entity entity;
        final ShockAnchors anchors = new ShockAnchors();
        /**
         * 锚点采集的<b>主接收器</b>：它自己不接顶点，只当"本帧这个实体要收锚点"的标记 +
         * "逐层接收器"的工厂（{@code ShockAnchors.Tap#forType}，按 RenderType 身份缓存）。
         * 逐层的意义：一次渲染里本体层 / 发光覆层 / 盔甲层各有各的贴图，UV 只属于自己那张图，
         * 所以每层必须拿自己那张 alpha 位图做判据（见 {@code ShockAnchors.Tap#bind}）。
         */
        final ShockAnchors.Tap tap = ShockAnchors.Tap.primary(anchors);
        final List<Arc> arcs = new ArrayList<>();

        /** 本帧该实体的模型顶点被收过（{@link #beginFrame} 置位、{@link #endFrame} 清除）。 */
        boolean fresh;
        /** "本帧有面却没有可用锚点"这条诊断日志是否已经打过（每个 Track 只打一次）。 */
        boolean warnedNoAnchor;
        /** 上一次算出来的目标条数（诊断用）。 */
        int target;
        /** 下一条绘制日志的时间戳（限流）。 */
        long nextLogMs;

        Track(Entity entity) {
            this.entity = entity;
        }
    }

    /**
     * 一条弧：<b>只存"锚点下标 + 相对偏移"</b>，端点逐帧由当前锚点现算。
     * 这就是"弧跟着模型走"的全部机制 —— 没有任何世界坐标快照（那会让弧在模型移动时被落下）。
     */
    private static final class Arc {

        /** 起点/终点的锚点下标。 */
        int ia, ib;
        /** 起点相对锚点的偏移（与锚点同一个视图空间，逐帧用「本帧锚点 + 它」复现端点）。 */
        float oax, oay, oaz;
        /** 终点相对锚点的偏移。 */
        float obx, oby, obz;

        /** 抖动形态种子（决定折线怎么扭）。 */
        int seed;
        float r, g, b;
        /** 逐条随机亮度倍率（两段长尾分布，见 {@link #sampleBright}）。 */
        float bright;
        /** 这条弧挂几根细丝（0~{@link #FILAMENT_MAX}，见 {@link #sampleFilaments}）。 */
        int filaments;
        /** 剩余 / 初始寿命（秒）。 */
        float life, maxLife;
    }
}
