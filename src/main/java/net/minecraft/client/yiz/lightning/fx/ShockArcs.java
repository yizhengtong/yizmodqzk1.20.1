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
 * <h2>锚点只在"贴图真正画到的地方"，且 2026-10-06 起"点数 ∝ 面的 3D 面积"</h2>
 * <p>用户复现（最简例子）："原版监守者有两个耳朵（{@code right_tendril}/{@code left_tendril}），耳朵在模型里
 * 是一块很大的平板，但贴图只画了一小部分，我们却在整块平板上绘制 ⇒ 闪电看起来覆盖了整个耳朵，
 * 变成大耳怪。" 病灶就是锚点直接取模型顶点：平板没画的角落也照样是锚点。</p>
 * <p>锚点<b>按面</b>取（每 4 个顶点一个 QUADS 面）：面的 UV 矩形内做网格采样，
 * 每个采样点查该层贴图的纹素 alpha（{@code ≥ 0.1}，与描边 {@code fsh} 那句 discard 同一阈值），
 * 通过的点用四角位置双线性插值出 3D 位置当锚点；<b>整面一个通过点都没有 ⇒ 这一面不产生任何锚点</b>。
 * 全部判据在 CPU 侧（{@link ShockAnchors} + {@code LockOutlineAlphaMask}/{@code LockOutlineLayerTexture}），
 * <b>本类一个字节的着色器/混合/采样器状态都没动</b>，渲染路径仍是
 * {@code LightningRenderer} + {@code ArcGeometry} + {@code rendertype_lightning_arc}。</p>
 * <p><b>本轮（用户："躯干胸部应该被包得更多"）改的两处，都在"锚点落在哪里"上</b>：</p>
 * <ol>
 *   <li><b>采样点数 ∝ 面的 3D 面积</b>（{@link ShockAnchors#SAMPLE_SPACING} = 0.15 格/点，
 *       单面 ≤{@link ShockAnchors#FACE_SAMPLE_MAX} 点，表 {@link ShockAnchors#CAPACITY}=2048）：
 *       旧规则把每面点数夹在 4~16、与面大小无关 ⇒ 躯干（最大的面）反而最稀，且 160 表抽稀后
 *       每个面的幸存点塌缩成一条线。离线实测：胸前那一面 4 点（一条线）→ 僵尸 18 点、监守者 70 点；
 *       各部位"表内点/可见格²"从 1.6~4 : 1 收敛到 <b>1.05 : 1（僵尸）/ 1.33 : 1（监守者）</b>。</li>
 *   <li><b>外推方向从"锚点−质心径向"改成"该面外法线"</b>（见 {@link #setOffset}）：
 *       躯干离质心最近 ⇒ 径向几乎与体表切向重合，弧被推进身体里（离线：约三成弧的起点落在本体内，
 *       僵尸胸前 27%、腿 42%）；改成法线后降到 ≤2% ⇒ 弧从体表垂直弹出，这才是"围绕"而不是"贴着皮"。</li>
 * </ol>
 * <p><b>观感参数一个都没改</b>：{@link #ARC_MAX} / {@link #APPARENT_GAIN} / {@link #ARC_TOTAL_MAX} /
 * {@link #OFF_MIN}~{@link #OFF_MAX} / {@link #JITTER} / {@link #SEGMENTS} / 长度两档 / 颜色两档 /
 * 细丝 / 寿命 / 亮度 / 顶点上限 / 渲染路径全部原样。
 * <b>注意上面这段只描述"按 alpha 过滤"那一轮</b>；另一轮（用户："特效过少，翻倍试试" + "要有明蓝" +
 * "控制电弧有长有短"）动过三处：<b>数量翻倍、颜色两档、长度两档</b>——全部集中在下面的常量区。</p>
 *
 * <h2>观感参数（每一条弧都是独立的生灭单元）</h2>
 * <ul>
 *   <li><b>寿命</b> {@link #LIFE_MIN}~{@link #LIFE_MAX} 秒，两端各有淡入淡出 ⇒ 不断生灭；</li>
 *   <li><b>数量</b>按"屏幕覆盖率"自适应：模型半径 / 到相机距离 决定目标条数，
 *       上限 {@link #ARC_MAX} 条/实体，并有全场总预算 {@link #ARC_TOTAL_MAX}（多目标时按比例降量）
 *       ⇒ 远处/多目标不会刷爆；</li>
 *   <li><b>偏移</b>沿"该锚点相对模型质心的径向外"推 {@link #OFF_MIN}~{@link #OFF_MAX} 格
 *       （外加 {@link #JITTER} 的随机抖动）⇒ 弧悬在体外那一圈空间里，不贴皮肤；</li>
 *   <li><b>长度：两档随机（1/3 长、2/3 短）</b>。第二个端点仍取"离第一个锚点最近的候选锚点"
 *       （{@link #NEIGHBOR_TRIES} 次随机试探里挑最近的）⇒ 方向天然是体表局部的；
 *       长度则改成<b>两档分布</b>：{@link #SEG_SHORT_FRAC}(2/3) 的弧落在
 *       {@link #SEG_SHORT_MIN}~{@link #SEG_SHORT_MAX} 格（贴身小跳弧），
 *       {@link #SEG_LONG_FRAC}(1/3) 落在 {@link #SEG_LONG_MIN}~{@link #SEG_LONG_MAX} 格
 *       （偶尔窜出去的粗长弧，还额外乘 {@link #SEG_LONG_WIDTH_GAIN} 粗一点）；
 *       两档之间留空档，所以"有长有短"一眼可辨。长度仍然只用"改第二个端点的偏移"实现，
 *       且长档的方向会把"指向体内"的径向分量翻成向外（见 {@link #clampLength}）⇒ 长而不穿身；</li>
 *   <li><b>颜色：两档随机（明蓝 / 白蓝）</b>。{@link #BLUE_FRAC} 的弧走<b>明蓝档</b>
 *       （更饱和的蓝，{@link #COLOR_BLUE_R_MIN}~{@link #COLOR_BLUE_R_MAX} /
 *       {@link #COLOR_BLUE_G_MIN}~{@link #COLOR_BLUE_G_MAX} / {@link #COLOR_BLUE_B}），
 *       其余走<b>白蓝档</b>（近白偏蓝，{@link #COLOR_WB_R_MIN}~{@link #COLOR_WB_R_MAX} /
 *       {@link #COLOR_WB_G_MIN}~{@link #COLOR_WB_G_MAX} / {@link #COLOR_WB_B}）；
 *       并且同一条弧内再分层：<b>主干（芯）用上面这档、外辉光带用更偏明蓝的一档</b>
 *       （{@link #HALO_BLUE_R} / {@link #HALO_WB_R} …）⇒ 近看是"白芯 + 蓝晕"的真实电弧，
 *       中远处也留得住蓝，不会糊成一片白灰；</li>
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
 * 本轮翻倍后：每实体上限 {@link #ARC_MAX}(88) × 最坏 104 = <b>9152 顶点/帧</b>（典型 ≈6.9k）；
 * 全场 {@link #ARC_TOTAL_MAX}(280) 最坏 <b>29120 顶点/帧</b>（典型 ≈22.2k），
 * 落在 {@link #FRAME_VERT_MAX}(36400 = 最坏 × 1.25) 之内 ⇒ 上限<b>不会</b>把最坏情况截掉。
 * 作为对照：编号 1 的单条链式闪电 ≈1500 顶点；本 pass 的顶点格式 24 字节/顶点，
 * 29120 顶点 ≈ 683 KB，而 1.20.1 的 {@code Tesselator} 那个 BufferBuilder 初始容量是 12.58 MB
 * （且写满会按 2 MB 取整自动扩容）—— 余量 18 倍，不糊屏也不爆缓冲。
 * 真正的数字每 1.5 秒由 {@code [ShockArc] 绘制: … 顶点=V} 打在日志里，不必估。</p>
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
     * <p>2026-10-04 第二轮（用户："还要更多的特效"）24 → 44；<b>本轮（用户："目前特效过少，翻倍试试"）
     * 44 → 88</b>：密度再翻一倍，近距离大目标（监守者 ≈2.2 格半径）在 8 格内就吃满上限。
     * 顶点预算见 {@link #FRAME_VERT_MAX} 的说明 —— 单实体 88 条 × 最坏 104 顶点 = <b>9152 顶点/帧</b>，
     * 典型（细丝 0.45 根/弧）≈ 6.9k；全场最坏 280 条 = 29120 顶点/帧（≈683 KB，
     * 对照本 pass 那个 12.58 MB 的 BufferBuilder 只用 5.4%），与编号 1 的单条链式闪电（≈1500）
     * 相比仍是同一量级。</p>
     */
    private static final int ARC_MAX = 88;
    /** 单实体弧数下限：再远也要有"零星几道"，否则远处目标看着完全没电。 */
    private static final int ARC_MIN = 3;
    /**
     * 目标条数 = 该系数 × (模型半径 / 到相机距离)。
     * 实测锚点云的半径：玩家 ≈0.95 格（头顶到脚底的一半多一点）⇒ 4 格外目标 ≈76 条、
     * 8 格外 ≈38 条、20 格外 ≈15 条、40 格外 ≈8 条；换成体型更大的目标（监守者 ≈2.2 格），
     * 分子变大 ⇒ 7~8 格内就吃满 {@link #ARC_MAX}。
     *
     * <p>160 → <b>320</b>（本轮翻倍）只是把整条曲线同比例抬高。远处仍然自动降量：目标条数依旧是
     * {@code Math.max(ARC_MIN, Math.min(ARC_MAX, GAIN × radius / dist))}（{@link #targetCount}），
     * 衰减关系一个字没动 ⇒ <b>近处多、远处自动少</b>，只是每一档都多了一倍。</p>
     */
    private static final float APPARENT_GAIN = 320f;
    /**
     * 每 tick 最多补生几条（补生速率上限）。它与寿命共同决定稳态条数：
     * 稳态 ≈ 本值 × 20 × 平均寿命 = 14 × 20 × 0.325s ≈ 91 条 ⇒ 刚好够把 {@link #ARC_MAX}(88)
     * 填满。<b>7 → 14 必须与 ARC_MAX 一起翻倍</b>：上一轮的稳态 ≈45 条本来就是按 44 条的上限配的，
     * 如果只翻 ARC_MAX 不翻本值，上限永远补不满 ⇒ 等于没翻倍。
     * 远处由屏幕覆盖率把目标条数压下去、根本补不到这个速率。
     */
    private static final int SPAWN_PER_TICK = 14;
    /**
     * <b>全场弧数总预算（跨所有感电实体）</b>：目标条数之和超过它时，所有实体按同一比例缩水。
     *
     * <p>为什么必须有这一条：{@link #ARC_MAX} 是<b>每实体</b>上限，而现场日志里一次出现过
     * 8 个监守者同时感电（latest.log 03:10:19 的 warden#1/#5/#7/…/#19）——
     * 8 × 88 条 = 704 条、最坏 ≈73k 顶点/帧，没有总预算就会线性堆到爆。有了总预算，
     * "多目标"这种极端情况自动降级为"每个目标少几条"，而不是线性堆到爆。</p>
     *
     * <p>140 → <b>280</b>（本轮随 {@link #ARC_MAX} 一起翻倍）：3 个满档目标（3 × 88 = 264 ≤ 280）
     * 仍能各自吃满，第 4 个起才开始按比例缩水。</p>
     */
    private static final int ARC_TOTAL_MAX = 280;
    /**
     * <b>全场每帧顶点硬上限（兜底）</b>：{@link #ARC_MAX} 与 {@link #ARC_TOTAL_MAX} 限的是<b>条数</b>，
     * 只要参数被改坏（例如把 {@code FILAMENT_MAX} 调大、或把 {@code SEGMENTS} 调大）顶点数仍可能
     * 成倍上涨。这一条限的是<b>顶点</b>本身，是本 pass 的最后一道保险：累计到上限就停止写顶点
     * （这一帧剩下的弧不画，下一帧照常），并且限流打一条 warn。</p>
     *
     * <p><b>取值口径 = 全场最坏估算 × 1.25</b>：{@link #ARC_TOTAL_MAX}(280) × 最坏单弧
     * （主干 {@code 12×SEGMENTS(6)}=72 + {@code FILAMENT_MAX(2)×16}=32 ⇒ 104）= <b>29120</b>；
     * 29120 × 1.25 = <b>36400</b>。</p>
     *
     * <p><b>⚠ 为什么必须跟着 ARC_TOTAL_MAX 一起上调</b>：20000 是"140 条时代"的数
     * （140 × 104 = 14560，留 37% 余量）。本轮全场最坏变成 29120 &gt; 20000 ⇒ 若不同步上调，
     * 最坏情况会被这道保险<b>静默截断</b>（后半场的弧整批不画），那"翻倍"就是假的。
     * 36400 恰好让最坏情况重新落回"保险线以内 20%"，护栏的语义不变：
     * <b>正常路径碰不到，碰到就说明有参数被改坏了</b>。</p>
     *
     * <p><b>为什么 36400 这个数本身也安全</b>（不是拍脑袋）：本 pass 的顶点格式是
     * {@code DefaultVertexFormat.POSITION_TEX_COLOR} = <b>24 字节/顶点</b>，36400 × 24 = 873600 字节
     * ≈ 853 KB；而 1.20.1 的 {@code Tesselator} 用的是 {@code new BufferBuilder(2097152)}，
     * BufferBuilder 构造里 {@code capacity = 入参 × 6} = <b>12.58 MB</b>，并且每写完一个顶点都会检查
     * "装不下就按 2 MB 取整扩容"（{@code BufferBuilder} 的私有扩容方法，只打一条 DEBUG 日志）。
     * ⇒ 即使真的顶到 36400，也只用到 6.8% 容量，既不会越界也不会触发扩容。</p>
     */
    private static final int FRAME_VERT_MAX = 36400;

    // ── 偏移 / 长度（格）──
    /** 从体表往外推的最小距离：太贴会和本体/盔甲打架。 */
    private static final float OFF_MIN = 0.05f;
    /** 往外推的最大距离：这一圈厚度就是"弧飘在体外"的体积感来源（0.35 → 0.50：弧层更厚、空间感更强）。 */
    private static final float OFF_MAX = 0.50f;
    /** 偏移方向的随机抖动（每轴 ±该值）：免得所有弧都规规矩矩沿法线排（0.06 → 0.10）。 */
    private static final float JITTER = 0.10f;

    /**
     * <b>长度硬边界（格）</b>：任何档位的目标长度最终都被夹进这里 —— 这就是"长度绝不能越过"的那条线。
     * 夹取手段不变，仍是<b>改第二个端点的偏移</b>（见 {@link #clampLength}），所以夹完的弧依旧
     * "锚点 + 偏移"、依旧跟着模型走。
     * 0.14~0.60（上一版是这一段里的均匀分布）→ <b>0.10~0.95</b>：下限略降让贴身小跳弧更碎，
     * 上限抬到 0.95 给长档留出"窜出去"的空间。
     */
    private static final float SEG_MIN = 0.10f;
    /** 见 {@link #SEG_MIN}。 */
    private static final float SEG_MAX = 0.95f;
    /**
     * <b>短档</b>（概率 {@link #SEG_SHORT_FRAC} = 2/3）：0.10~0.28 格 —— 贴着体表的小跳弧，
     * 数量多、密度高，负责"全身在噼啪"。
     */
    private static final float SEG_SHORT_MIN = 0.10f;
    /** 见 {@link #SEG_SHORT_MIN}。 */
    private static final float SEG_SHORT_MAX = 0.28f;
    /** 短档概率：<b>2/3</b>（与 {@link #SEG_LONG_FRAC} 互补，两档加起来正好 1）。 */
    private static final float SEG_SHORT_FRAC = 1f - (1f / 3f);
    /**
     * <b>长档</b>（概率 {@link #SEG_LONG_FRAC} = 1/3）：0.45~0.95 格 —— 偶尔窜出去的粗长弧，
     * 负责"闪电"的力量感与层次。
     *
     * <p>两档之间<b>刻意留 0.28~0.45 的空档</b>：分布不连续，肉眼才分得出"这群里有几条明显长"。
     * 上一版是 0.14~0.60 的<b>均匀分布</b> ⇒ 长度只是均值附近的噪声，看着"所有弧一样长"
     * （正是用户这次提的问题）。</p>
     */
    private static final float SEG_LONG_MIN = 0.45f;
    /** 见 {@link #SEG_LONG_MIN}。 */
    private static final float SEG_LONG_MAX = 0.95f;
    /**
     * 长档的概率：<b>1/3 长、2/3 短</b>（用户指定）。
     *
     * <p>实现见 {@link #spawn}：每生成一条弧就独立掷一次 {@code rnd.nextFloat() < SEG_LONG_FRAC}，
     * 长档用它、其余用短档 —— 是"逐弧独立随机"，不是"前 1/3 条长、后 2/3 条短"。
     * 掷一次骰子同时决定长度档与线宽档，两者永远一致。</p>
     */
    private static final float SEG_LONG_FRAC = 1f / 3f;
    /**
     * 长档的额外线宽倍率（1.25 = 粗 1/4）：用户要的是"偶尔窜出去的<b>粗</b>长弧"，
     * 于是长档在"长度 + 粗细"两个维度上同时分层（颜色维度由 {@link #BLUE_FRAC} 独立掷）。
     * 只乘在 {@link #HALF_WIDTH} 上，不碰任何渲染状态。
     */
    private static final float SEG_LONG_WIDTH_GAIN = 1.25f;
    /** 挑第二个锚点时随机试探的次数：从这几次里挑离第一个最近的 ⇒ 弧是短的、局部的。 */
    private static final int NEIGHBOR_TRIES = 6;

    // ── 颜色（两档，本轮新增）──
    //  ⚑ 着色器约定（先读这一条）：rendertype_lightning_arc.fsh 只读 vColor.rgb，
    //    vColor.a 一个像素都不参与（它自己的 alpha 由 core/flick/近距淡出算），
    //    所以"颜色 / 亮度 / 生灭"必须全部落在 RGB 与线宽上 —— 往 alpha 上写东西是不生效的。
    //    下面两档就是 RGB 上的两个色阶（外加更偏蓝的光晕档）。
    /**
     * <b>明蓝档</b>（用户要恢复的那一档）：更饱和的蓝，{@code r∈[MIN,MAX]}、{@code g∈[MIN,MAX]}、
     * {@code b = 1}。它就是"闪电的蓝"：中远处弧细到 1~3 像素时，画面上剩下的主要是带子两侧翼的
     * 颜色（芯会被 fsh 的白化混成白），明蓝档在那种条件下仍然读成蓝，白蓝档会读成白。
     */
    private static final float COLOR_BLUE_R_MIN = 0.25f, COLOR_BLUE_R_MAX = 0.40f;
    private static final float COLOR_BLUE_G_MIN = 0.55f, COLOR_BLUE_G_MAX = 0.70f;
    private static final float COLOR_BLUE_B = 1.00f;
    /** <b>白蓝档</b>（过曝的电弧芯，也就是上一版那一档）：近白偏蓝。 */
    private static final float COLOR_WB_R_MIN = 0.75f, COLOR_WB_R_MAX = 0.90f;
    private static final float COLOR_WB_G_MIN = 0.88f, COLOR_WB_G_MAX = 0.96f;
    private static final float COLOR_WB_B = 1.00f;
    /**
     * 明蓝档的比例：<b>0.40</b> = 每 5 条里 2 条明蓝、3 条白蓝（落在用户建议的 1/3~1/2 中间）。
     * 实现见 {@link #sampleColor}：逐弧独立掷一次，与长度档那次掷骰互不影响
     * （所以"长弧"和"明蓝"是四种组合都可能出现，不会绑成一种）。
     */
    private static final float BLUE_FRAC = 0.40f;
    /**
     * <b>外辉光带（光晕）的颜色：核心偏白、光晕偏明蓝</b> —— 真实电弧就是这个层次
     * （电离芯过曝成白、外围复合发蓝），而且外圈那层蓝边正是"中远处不发灰"的保险：
     * fsh 把每条带子中间 35% 往白里混（{@code mix(vColor.rgb, 1.0, white*0.9)}），
     * 光晕带比主干宽 3 倍，它的外侧大部分区域保留的就是这两个值 ⇒ 蓝留得住。
     * 明蓝档的光晕更深（0.10/0.32/1.00），白蓝档是"浅一档的蓝"（0.34/0.56/1.00）。
     */
    private static final float HALO_BLUE_R = 0.10f, HALO_BLUE_G = 0.32f, HALO_BLUE_B = 1.00f;
    private static final float HALO_WB_R = 0.34f, HALO_WB_G = 0.56f, HALO_WB_B = 1.00f;

    // ── 几何形态 ──
    /** 单条弧的锯齿折线段数（5 → 6：弧更长、折点更多，"噼啪"的细节更足）。 */
    private static final int SEGMENTS = 6;
    /** 单条弧最多挂几根细丝（{@code ArcGeometry.emitShortArc} 的分支）。 */
    private static final int FILAMENT_MAX = 2;
    /** 挂细丝的概率（低概率 ⇒ "电弧丛"；每条弧都挂就成了毛球）。 */
    private static final float FILAMENT_CHANCE = 0.30f;
    /**
     * 主干半宽（格）：2.8 cm ⇒ 4 格外看过去约 8~10 像素、10 格外约 3~4 像素，
     * 比编号 1 的链式电弧（0.05）细一档 —— 弧本身只有 0.10~0.95 格长，太粗就成了"光棒"。
     * 实际画出来的半宽还会乘一条随亮度变化的系数、长档再乘 {@link #SEG_LONG_WIDTH_GAIN}
     * （见 {@link #emit}）。
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
                                + "；锚点按「该层贴图 alpha」过滤：面 → UV 矩形网格采样 → 纹素 alpha≥0.1 "
                                + "→ 四角双线性插值出位置，"
                                + "贴图没画到的地方（例如监守者耳朵那块平板的空白区）一个锚点都不产生"
                                + "；本轮起【点数 ∝ 面的 3D 面积】：间距 {} 格/点、单面 ≤{} 点、表 {} 点"
                                + "（面积越大拿到的锚点越多，且各部位单位面积点数一致 ⇒ 躯干/胸前不再是最稀的地方）"
                                + "；【外推方向 = 该面外法线】（旧写法沿“锚点−质心”径向，躯干的径向几乎与体表切向重合，"
                                + "离线实测约 3 成弧的起点被推进身体里 ⇒ 看不到）",
                        MirrorDiag.entity(entity), ShockAnchors.CAPACITY,
                        fmt(ShockAnchors.SAMPLE_SPACING), ShockAnchors.FACE_SAMPLE_MAX, ShockAnchors.CAPACITY);
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
     * 生成一条弧：两个锚点下标 + 两段相对偏移 + 随机种子/亮度/寿命/颜色/长度档。
     *
     * <p><b>只在生成时算一次几何参数</b>：偏移是与锚点同一个空间（视图空间）里的常量向量，
     * （该空间与世界只差一个平移，方向不变），所以逐帧渲染时只要"本帧锚点 + 这个偏移"
     * 就能复现出跟着模型走的弧 —— 不需要每帧重新随机，也不会每帧抖动到别处。</p>
     *
     * <p>这里掷<b>两枚互不相关的骰子</b>：一枚定长度档（1/3 长、2/3 短，同时决定线宽），
     * 一枚定颜色档（{@link #BLUE_FRAC} 明蓝、其余白蓝）。</p>
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

        // ── 长度档：一枚骰子定档（1/3 长、2/3 短），长度与线宽都跟着这一档走 ──
        a.longArc = rnd.nextFloat() < SEG_LONG_FRAC;
        float segLen = sampleSegLen(rnd, a.longArc);

        // ── 颜色档：另一枚骰子定档（明蓝 / 白蓝），同时定该档的"芯色 + 光晕色" ──
        sampleColor(a, rnd);

        // 偏移：沿"该锚点相对模型质心的径向外"推 OFF 格 + 每轴随机抖动 ⇒ 弧悬在体外那一圈空间里
        setOffset(an, ia, 0, a, rnd);
        setOffset(an, ib, 1, a, rnd);

        clampLength(an, a, rnd, segLen);
        return a;
    }

    /**
     * 逐弧抽一个目标长度：<b>1/3 长档、2/3 短档</b>（两档内各自均匀取）。
     *
     * <p>为什么必须是"两段"而不是"一个区间里均匀取"：均匀分布下长度只是均值附近的噪声，
     * 一堆 0.1~0.9 的弧混在一起，肉眼读成"所有弧都差不多长"（正是用户这次的反馈）。
     * 两段分布 + 中间空档（{@link #SEG_SHORT_MAX}~{@link #SEG_LONG_MIN} 之间不取值）
     * 才让"短的多、长的少"一眼可辨。</p>
     *
     * @param longArc {@link #spawn} 掷出来的档位（长 = true）
     */
    private static float sampleSegLen(ThreadLocalRandom rnd, boolean longArc) {
        return longArc
                ? SEG_LONG_MIN + rnd.nextFloat() * (SEG_LONG_MAX - SEG_LONG_MIN)
                : SEG_SHORT_MIN + rnd.nextFloat() * (SEG_SHORT_MAX - SEG_SHORT_MIN);
    }

    /**
     * 逐弧抽一档颜色：<b>明蓝</b>（{@link #BLUE_FRAC} 的概率）或<b>白蓝</b>（其余），
     * 并同时写入该档的<b>芯色</b>（{@code a.r/g/b}，主干两条交叉带用）与
     * <b>光晕色</b>（{@code a.hr/hg/hb}，外辉光带用，见 {@link #HALO_BLUE_R} 的说明）。
     *
     * <p>两档都是"区间内均匀取"，所以同一档里也没有两条颜色完全一样的弧 ——
     * 一档一个固定色会看出"复制粘贴"，一个区间才有"每一道都是新的"。</p>
     */
    private static void sampleColor(Arc a, ThreadLocalRandom rnd) {
        boolean blue = rnd.nextFloat() < BLUE_FRAC;
        a.blueArc = blue;
        float r0 = blue ? COLOR_BLUE_R_MIN : COLOR_WB_R_MIN;
        float r1 = blue ? COLOR_BLUE_R_MAX : COLOR_WB_R_MAX;
        float g0 = blue ? COLOR_BLUE_G_MIN : COLOR_WB_G_MIN;
        float g1 = blue ? COLOR_BLUE_G_MAX : COLOR_WB_G_MAX;
        a.r = r0 + rnd.nextFloat() * (r1 - r0);
        a.g = g0 + rnd.nextFloat() * (g1 - g0);
        a.b = blue ? COLOR_BLUE_B : COLOR_WB_B;
        a.hr = blue ? HALO_BLUE_R : HALO_WB_R;
        a.hg = blue ? HALO_BLUE_G : HALO_WB_G;
        a.hb = blue ? HALO_BLUE_B : HALO_WB_B;
    }

    /**
     * 算一个锚点的偏移向量，直接写进弧的字段（{@code which} 0 = 起点、1 = 终点）。
     *
     * <h2>2026-10-06：外推方向从"锚点−质心径向"改成"该锚点所在面的外法线"</h2>
     * <p><b>病灶</b>：径向在<b>四肢和头</b>上还凑合，但<b>躯干离质心最近</b>（人体模型的质心基本就在
     * 躯干里）⇒ 躯干锚点的"径向"几乎与体表<b>切向</b>重合，{@link #OFF_MIN}~{@link #OFF_MAX} 那点
     * 外推量根本推不出体表，反而被 {@link #JITTER}(±0.10/轴) 掀到体表<b>以内</b>；
     * 起点一旦落在本体里，随后就被本体的深度值挡掉（本 pass 只关深度<b>写</b>、没关深度测试）
     * ⇒ 看起来"躯干上几乎没有弧"，剩下的那些也像"贴着皮"。</p>
     * <p><b>离线证据</b>（{@code _scan/AnchorLayoutProbe}，真 warden.png / zombie.png，
     * 每个锚点 32 次随机抽样，判据 = 偏移在面外法线上的分量 &lt; 0 即"起点落进本体"）：</p>
     * <pre>
     *   沿径向（旧）: 监守者 合计 22%  胸前 7%  头 22%  臂 28%  腿 37%  触须/肋甲 24%
     *                僵尸   合计 31%  胸前 27% 头 23%  臂 35%  腿 42%
     *   沿面外法线（新）: 监守者 全部 ≤2%；僵尸 全部 ≤2%
     * </pre>
     * <p>⇒ 旧写法让<b>约三成的弧起点埋在身体里</b>，躯干正面尤其明显（僵尸胸前 27%）。换成面外法线后，
     * 弧是从体表<b>垂直</b>弹出去的，这才是"围绕实体"而不是"贴着皮"。</p>
     * <p><b>方向怎么定</b>（{@link ShockAnchors#nx}/{@code ny}/{@code nz}，采集期由面的四个角点叉积得到）：</p>
     * <ol>
     *   <li><b>叉积为 0</b>（零厚度平板的棱面、共线顶点）⇒ 该锚点存的是 0，这里退回旧的"径向"
     *       —— 与改造前一致，绝不因为拿不到法线就不出弧；</li>
     *   <li><b>叉积非 0</b> ⇒ 单位化；再用"锚点−质心"判一次<b>哪边是外</b>：原版未镜像的面叉积天然朝外
     *       （离线自检 44/44），但镜像过的面有少数朝内（僵尸左臂/左腿各 2 个面），
     *       判据是 {@code dot(法线, 径向) < −0.25} ⇒ 翻过来。阈值取 −0.25 而不是 0 是<b>故意的</b>：
     *       薄平板（监守者触须/肋甲）的"径向"本身就在板面内（离线 80~81% 的点 |dot|&lt;0.10），
     *       拿它当判据会把本来正确的法线翻坏；只有"明显反向"（镜像肢体那种 dot≈−1）才翻。</li>
     * </ol>
     * <p>{@link #JITTER} 与外推距离一个字节都没动（用户已确认的观感参数），变的只是"往哪个方向推"。</p>
     */
    private static void setOffset(ShockAnchors an, int i, int which, Arc a, ThreadLocalRandom rnd) {
        float dx = an.x(i) - an.centroidX();
        float dy = an.y(i) - an.centroidY();
        float dz = an.z(i) - an.centroidZ();
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        float dist = OFF_MIN + rnd.nextFloat() * (OFF_MAX - OFF_MIN);
        float ox, oy, oz;
        if (outwardDirection(an, i, dx, dy, dz, len, OUT) > 0f) {
            ox = OUT[0] * dist;
            oy = OUT[1] * dist;
            oz = OUT[2] * dist;
        } else {
            // 退化：随机方向（与下面 clampLength 的退化分支同一套球面均匀取法）
            double ang = rnd.nextDouble() * Math.PI * 2.0;
            float uy = rnd.nextFloat() * 2f - 1f;
            float sr = (float) Math.sqrt(Math.max(0f, 1f - uy * uy));
            ox = (float) (Math.cos(ang) * sr) * dist;
            oy = uy * dist;
            oz = (float) (Math.sin(ang) * sr) * dist;
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
     * 求第 {@code i} 个锚点的<b>朝外单位方向</b>，写进 {@link #OUT}（每帧复用的小缓存，零分配）。
     *
     * @param dx/dy/dz 该锚点相对模型质心的向量（调用方已算好），{@code len} 是它的长度
     * @return {@code > 0} = {@link #OUT} 有效（面外法线，或退化时的径向）；{@code ≤ 0} = 两种方向都退化
     *         （锚点正好在质心且面法线为 0）⇒ 调用方自行取随机方向
     */
    private static float outwardDirection(ShockAnchors an, int i, float dx, float dy, float dz, float len, float[] out) {
        float nx = an.nx(i), ny = an.ny(i), nz = an.nz(i);
        float nl = nx * nx + ny * ny + nz * nz;
        if (nl > 1.0e-9f && len > 1.0e-4f) {
            float inv = 1f / (float) Math.sqrt(nl);
            nx *= inv; ny *= inv; nz *= inv;
            // 用质心判"哪边是外"：镜像部件 / 反向绕组的模型叉积可能朝内（见 setOffset 的判据说明）
            if (nx * dx + ny * dy + nz * dz < -0.25f * len) { nx = -nx; ny = -ny; nz = -nz; }
            out[0] = nx; out[1] = ny; out[2] = nz;
            return 1f;
        }
        if (len > 1.0e-4f) {                       // 退化面：退回径向（= 改造前的方向）
            out[0] = dx / len; out[1] = dy / len; out[2] = dz / len;
            return 1f;
        }
        return 0f;
    }

    /** {@link #outwardDirection} 的返回缓存（只在本类内部、只在客户端主线程用 ⇒ 无竞争、零分配）。 */
    private static final float[] OUT = new float[3];

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

    /**
     * 把两端距离夹到本弧的目标长度 {@code want}（<b>通过改第二个端点的偏移实现</b>，第一个端点一个字不动）。
     *
     * <p>这是"长度"唯一的落地处，也是"夹取后仍跟随模型"的关键：结果写回 {@code a.ob*} =
     * "第二个端点 − 第二个锚点"，逐帧仍旧用"本帧锚点 + 这个偏移"复现 ⇒ 弧永远跟着模型。</p>
     *
     * <p>三种情形：</p>
     * <ol>
     *   <li>{@code d > segLen}：把第二个端点沿两端连线<b>往回收</b>到 segLen；</li>
     *   <li>{@code d < segLen}：沿连线方向<b>往外推</b>到 segLen（长档主要靠这一支：
     *       最近邻锚点常常只有几厘米，靠外推才够 0.45~0.95 格）；</li>
     *   <li>{@code d ≈ 0}（两个锚点落在同一点上）：没有方向可用，直接沿"该点相对模型质心的径向外"
     *       推 segLen 格；连径向都退化（点就在质心上）才退回随机球面方向 —— 绝不留 NaN。</li>
     * </ol>
     * <p>两种情形最终都写成同一句 {@code ob = p0 + û · segLen − anchor_b}：所以无论走哪一支，
     * 弧的长度<b>恰好</b>是抽到的那一档（不是"最多"），且端点仍然只存成"相对第二个锚点的偏移"
     * ⇒ 逐帧跟随模型这件事不受影响。</p>
     *
     * <h2>长而不穿身（长档专属的一道方向修正）</h2>
     * <p>长档最长 0.95 格，方向如果"往身体里"指，端点就会扎进躯干（视觉上被本体深度挡掉，
     * 或者读成一条钻进身体的长线）。所以当 {@code segLen > SEG_SHORT_MAX}（= 长档）时，
     * 先把方向里<b>指向体内</b>的径向分量按镜面翻成向外：{@code û' = û − 2(û·r̂)r̂}
     * （{@code r̂} = 起点相对模型质心的单位径向；镜面只翻转径向分量，<b>切向分量与模长都不变</b>），
     * 于是长弧只会"沿体表切线 / 外法线窜出去"。三重保证合起来：</p>
     * <ol>
     *   <li>最近邻策略（{@link #nearestAnchor}）保证第二个锚点来自体表局部，方向本来就贴着表面；</li>
     *   <li>上面那道径向镜面保证长档的方向<b>不含向内分量</b>（端点不会比起点更靠内）；</li>
     *   <li>本体是不透明的、且在本 pass 之前就写好了深度值，任何仍然贴着/进入身体的部分会被
     *       深度测试遮掉（本 pass 只关深度<b>写</b>、没关深度测试）—— 所以绝不会出现
     *       "一道亮线从手穿到脚"糊在屏幕上。</li>
     * </ol>
     * <p><b>短档刻意不做这道修正</b>：短档最长 0.28 格，还够不着身体另一侧，改了只是白白
     * 改动上一版已经确认过的观感。于是"短弧怎么长、往哪长"与上一版逐字等价，
     * 变的只是长度区间（0.14 固定下限 → 0.10~0.28 随机）。</p>
     *
     * @param want 本弧的目标长度（来自 {@link #sampleSegLen}），会被再夹一道
     *             {@link #SEG_MIN}~{@link #SEG_MAX} 硬边界（防参数被改坏）
     */
    private static void clampLength(ShockAnchors an, Arc a, ThreadLocalRandom rnd, float want) {
        float segLen = Math.max(SEG_MIN, Math.min(SEG_MAX, want));
        float p0x = an.x(a.ia) + a.oax, p0y = an.y(a.ia) + a.oay, p0z = an.z(a.ia) + a.oaz;
        float ex = an.x(a.ib) + a.obx - p0x;
        float ey = an.y(a.ib) + a.oby - p0y;
        float ez = an.z(a.ib) + a.obz - p0z;
        float d = (float) Math.sqrt(ex * ex + ey * ey + ez * ez);

        float ux, uy, uz;
        if (d > 1.0e-4f) {
            ux = ex / d;
            uy = ey / d;
            uz = ez / d;
        } else {
            // ── 两端重合（d ≈ 0）：沿径向外推，径向也退化才用随机球面方向 ──
            float rx = p0x - an.centroidX(), ry = p0y - an.centroidY(), rz = p0z - an.centroidZ();
            float rn = (float) Math.sqrt(rx * rx + ry * ry + rz * rz);
            if (rn > 1.0e-4f) {
                ux = rx / rn;
                uy = ry / rn;
                uz = rz / rn;
            } else {
                double ang = rnd.nextDouble() * Math.PI * 2.0;
                float uy2 = rnd.nextFloat() * 2f - 1f;
                float sr = (float) Math.sqrt(Math.max(0f, 1f - uy2 * uy2));
                ux = (float) (Math.cos(ang) * sr);
                uy = uy2;
                uz = (float) (Math.sin(ang) * sr);
            }
        }

        // ── 长档专属：把方向里"指向体内"的径向分量翻成向外（见方法注释"长而不穿身"）──
        //  2026-10-06：这里的"径向"也换成了与 setOffset 同一个"朝外方向"（面外法线，退化面才用径向）
        //  —— 理由同上：躯干的径向几乎与体表切向重合，用它做镜面等于没做。
        if (segLen > SEG_SHORT_MAX) {
            float rx = p0x - an.centroidX(), ry = p0y - an.centroidY(), rz = p0z - an.centroidZ();
            float rn = (float) Math.sqrt(rx * rx + ry * ry + rz * rz);
            if (rn > 1.0e-4f) {
                if (outwardDirection(an, a.ia, rx, ry, rz, rn, OUT) > 0f) {
                    rx = OUT[0]; ry = OUT[1]; rz = OUT[2];
                } else {
                    rx /= rn; ry /= rn; rz /= rn;
                }
                float dot = ux * rx + uy * ry + uz * rz;
                if (dot < 0f) {
                    ux -= 2f * dot * rx;            // 镜面翻转径向分量（切向分量与模长都不变）
                    uy -= 2f * dot * ry;
                    uz -= 2f * dot * rz;
                    float ul = (float) Math.sqrt(ux * ux + uy * uy + uz * uz);
                    if (ul > 1.0e-4f) {
                        ux /= ul;
                        uy /= ul;
                        uz /= ul;
                    } else {                        // 理论上到不了（镜面不改变模长），纯兜底
                        ux = rx;
                        uy = ry;
                        uz = rz;
                    }
                }
            }
        }

        a.obx = p0x + ux * segLen - an.x(a.ib);
        a.oby = p0y + uy * segLen - an.y(a.ib);
        a.obz = p0z + uz * segLen - an.z(a.ib);
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
                    // 长档再乘 SEG_LONG_WIDTH_GAIN（"窜出去的粗长弧"里的那个"粗"）。
                    float lum = Math.min(1.0f, fade * a.bright * strength);
                    float halfWidth = HALF_WIDTH * (a.longArc ? SEG_LONG_WIDTH_GAIN : 1f)
                            * (0.60f + 0.40f * lum);
                    // 芯色（主干两条交叉带）与光晕色（外辉光带）分别给：核心偏白、光晕偏明蓝
                    // ⇒ 近看是"白芯 + 蓝晕"，中远处靠那圈蓝边留住"闪电的蓝"（见 HALO_BLUE_R 的说明）。
                    ArcGeometry.emitShortArc(vc, IDENTITY, from, to, a.seed, halfWidth,
                            a.r * lum, a.g * lum, a.b * lum,
                            a.hr * lum, a.hg * lum, a.hb * lum,
                            lum, time, SEGMENTS, a.filaments);
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
                                + "正常路径碰不到这个上限（{} 条 × 最坏 104 顶点 = {}）；"
                                + "碰到就说明 ARC_MAX/ARC_TOTAL_MAX/SEGMENTS/FILAMENT_MAX 有哪一项被改坏了",
                        FRAME_VERT_MAX, frameVerts, ARC_TOTAL_MAX, ARC_TOTAL_MAX * 104);
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
     * "贴图过滤有没有生效（面=/有画面=/空白面=/候选=/未过滤面=/过滤=）"
     * "两档随机分得对不对（<b>弧档实测=</b>）"。
     *
     * <p>{@code 顶点=} 是这一帧该实体真正写进缓冲的顶点数（主干 12×{@link #SEGMENTS} + 细丝 16/根），
     * 也就是<b>性能护栏的现场证据</b>：单实体上限 = {@link #ARC_MAX} × (12×{@link #SEGMENTS}
     * + {@link #FILAMENT_MAX}×16) = 88 × 104 = 9152 顶点/帧（典型 ≈6.9k）；全场
     * （{@link #ARC_TOTAL_MAX}=280 条）最坏 = 29120 顶点/帧（典型 ≈22.2k），
     * 而硬上限 {@link #FRAME_VERT_MAX}=36400 = 最坏 × 1.25 ⇒ 最坏情况也<b>碰不到</b>这道保险。
     * 与编号 1 的单条链式闪电（≈1500 顶点）相比仍是同一量级。</p>
     *
     * <p><b>本轮新增的 {@code 弧档实测=}</b>：直接数当前存活的弧里有几条长档、几条明蓝
     * （不是印参数，是印实际掷出来的分布）—— 盯着看几秒，{@code 长}应稳定在 <b>33%</b> 左右、
     * {@code 明蓝}应稳定在 <b>40%</b> 左右。这是"1/3 长、2/3 短"与"两档颜色"最省事的验证口径。</p>
     *
     * <p><b>锚点过滤那一组数字怎么读</b>（统计口径见 {@link ShockAnchors}）：
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
        // 实际掷出来的档位分布（长/明蓝各占多少）—— 验证"1/3 长、2/3 短"与颜色两档的现场证据
        int nLong = 0;
        int nBlue = 0;
        for (int i = 0; i < t.arcs.size(); i++) {
            Arc a = t.arcs.get(i);
            if (a.longArc) nLong++;
            if (a.blueArc) nBlue++;
        }
        LOGGER.info("[ShockArc] 绘制: entity={} 锚点={} 弧={}/目标={} 顶点={} 强度={} 半径={}格 到相机={}格 "
                        + "‖ 面={} 有画面={} 空白面={} 未过滤面={} 候选={} 采样={} 预算跳过面={} {} "
                        + "‖ 锚点规则=面积加权(间距{}格/面≤{}点, 表{}) 外推=面外法线 "
                        + "‖ 偏移={}~{}格 长度=短档 {}~{}格({}) + 长档 {}~{}格({}) 寿命={}~{}s 段数={} "
                        + "细丝≤{}根({}概率) 亮度={}~{}({}走长尾) "
                        + "颜色=明蓝({}概率, 芯 {}~{}~{} / 晕 {}~{}~{}) + 白蓝(芯 {}~{}~{} / 晕 {}~{}~{}) "
                        + "长档加粗×{} 弧档实测=长{}条({}) 明蓝{}条({}) "
                        + "上限={}条/实体 {}条/全场 顶点上限={}",
                MirrorDiag.entity(t.entity), an.count(), t.arcs.size(), t.target, verts,
                fmt(strength), fmt(an.radius()), fmt(an.centroidDistance()),
                an.statFaces(), an.statPaintedFaces(), an.statBlankFaces(), an.statUnfilteredFaces(),
                an.statOffers(), an.statSamples(), an.statBudgetFaces(), filter,
                fmt(ShockAnchors.SAMPLE_SPACING), ShockAnchors.FACE_SAMPLE_MAX, ShockAnchors.CAPACITY,
                fmt(OFF_MIN), fmt(OFF_MAX),
                fmt(SEG_SHORT_MIN), fmt(SEG_SHORT_MAX), pct(SEG_SHORT_FRAC),
                fmt(SEG_LONG_MIN), fmt(SEG_LONG_MAX), pct(SEG_LONG_FRAC),
                fmt(LIFE_MIN), fmt(LIFE_MAX), SEGMENTS, FILAMENT_MAX,
                pct(FILAMENT_CHANCE), fmt(BRIGHT_MIN), fmt(BRIGHT_MAX), pct(BRIGHT_TAIL_FRAC),
                pct(BLUE_FRAC),
                fmt(COLOR_BLUE_R_MIN), fmt(COLOR_BLUE_G_MIN), fmt(COLOR_BLUE_B),
                fmt(HALO_BLUE_R), fmt(HALO_BLUE_G), fmt(HALO_BLUE_B),
                fmt(COLOR_WB_R_MIN), fmt(COLOR_WB_G_MIN), fmt(COLOR_WB_B),
                fmt(HALO_WB_R), fmt(HALO_WB_G), fmt(HALO_WB_B),
                fmt(SEG_LONG_WIDTH_GAIN),
                nLong, pct(t.arcs.size() == 0 ? 0f : nLong / (float) t.arcs.size()),
                nBlue, pct(t.arcs.size() == 0 ? 0f : nBlue / (float) t.arcs.size()),
                ARC_MAX, ARC_TOTAL_MAX, FRAME_VERT_MAX);
    }

    /** 把一个 [0,1] 的比例印成百分数（诊断日志用，例如 0.3333 → {@code 33%}）。 */
    private static String pct(float frac) {
        return String.format(java.util.Locale.ROOT, "%.0f%%", frac * 100f);
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
        /** 芯色（主干两条交叉带用）：明蓝档或白蓝档，见 {@link #sampleColor}。 */
        float r, g, b;
        /** 光晕色（外辉光带用）：比芯色更偏明蓝，见 {@link #HALO_BLUE_R}。 */
        float hr, hg, hb;
        /** 是否长档（{@link #SEG_LONG_FRAC} 那 1/3）：决定长度区间与线宽倍率。 */
        boolean longArc;
        /** 是否明蓝档（{@link #BLUE_FRAC} 那 40%）：诊断日志据此统计实际分布。 */
        boolean blueArc;
        /** 逐条随机亮度倍率（两段长尾分布，见 {@link #sampleBright}）。 */
        float bright;
        /** 这条弧挂几根细丝（0~{@link #FILAMENT_MAX}，见 {@link #sampleFilaments}）。 */
        int filaments;
        /** 剩余 / 初始寿命（秒）。 */
        float life, maxLife;
    }
}
