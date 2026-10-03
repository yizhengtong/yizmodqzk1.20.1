package net.minecraft.client.yiz.lightning.fx;

import com.mojang.blaze3d.vertex.DefaultedVertexConsumer;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.yiz.client.render.LockOutlineAlphaMask;
import net.minecraft.client.yiz.client.render.LockOutlineLayerTexture;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * 围弧（编号 4）的<b>锚点采样表</b> —— 把实体模型顶点里的一小撮坐标留下来，供
 * {@link ShockArcs} 当"弧从哪里长出来 / 长到哪里去"的锚点。
 *
 * <h2>为什么锚点必须取"模型自己写出的顶点"</h2>
 * <p>顶点被写出的那一刻，坐标<b>已经</b>是最终坐标（原版把视图旋转、相机相对平移、插值位置、姿态、
 * 倾斜、缩放全部乘进 {@code PoseStack} 了，见 {@code ModelMirrorBufferSource} 类注释的链条）。
 * 于是：</p>
 * <ul>
 *   <li>锚点天然跟着模型走：别的模组把模型挪到 10 格外、击飞倒下、骨骼动画、骑乘偏转 ——
 *       顶点被搬到哪，锚点就在哪，<b>本类从头到尾没有"实体世界位置"这个概念</b>；</li>
 *   <li>坐标是<b>视图空间</b>（相机在原点，视图旋转已经烤进顶点 —— 这正是"实体渲染期间
 *       ModelViewMat 是单位阵、模型却仍能正确朝向屏幕"的唯一解释，取证见
 *       {@code rendertype_flame_surface.vsh} 的坐标系说明）。弧的顶点因此要用<b>单位阵</b>写入，
 *       与实体本体、与贴模型火焰逐字同一个空间；<b>不要</b>再乘关卡姿态矩阵
 *       （那是给"相机相对世界坐标"用的），否则视图旋转会被乘两遍。</li>
 * </ul>
 *
 * <h2>2026-10-05：锚点只在"贴图真正画到的地方"（按该层贴图 alpha 过滤）</h2>
 * <p><b>病灶（用户原话）</b>：原版监守者的两根"耳朵"（{@code right_tendril}/{@code left_tendril}）
 * 在模型里是 16×16 像素大的一块平板（{@code addBox(-16,-13,0, 16,16,0)} = 1×1 格），
 * 而 {@code warden.png} 里那块区域<b>只有 18.8% 的纹素真的画了东西</b>
 * （离线用真图统计：(52,32)-(68,48) 共 256 纹素、不透明 48 个）。
 * 改造前锚点 = 模型顶点 ⇒ 这块空白平板的 4 个角照样成为锚点，
 * 弧于是散布在整片平板上 —— 用户看到的就是"大耳怪"。</p>
 *
 * <p><b>判据（与描边镂空同源，全部 CPU 侧）</b>：不再"逐顶点取锚点"，而是
 * <b>按面（每 4 个顶点一个 QUADS 面）</b>处理：</p>
 * <ol>
 *   <li>面的 4 个顶点同时给出<b>位置</b>与 <b>UV0</b>（{@code ModelPart.Cube} 写的就是图集归一化 UV，
 *       见 {@code ModelPart.Cube#compile} 的第 14 参 {@code modelpart$vertex.u/v}）；</li>
 *   <li>取该面 UV 矩形的 min/max，换算成纹素跨度；<b>在该 UV 矩形内做小网格采样</b>
 *       （{@code n×n}，{@code n∈[2,4]}，按纹素面积自适应 ⇒ 每面最多 16 次 alpha 查表）；</li>
 *   <li>每个采样点落在哪个纹素 → {@link LockOutlineAlphaMask#opaqueTexel} 判 {@code alpha >= 0.1}
 *       （与 {@code rendertype_lock_fill.fsh} 那句 {@code if (tex.a < 0.1) discard;} 同一个阈值）；</li>
 *   <li><b>通过</b>的采样点，用四边形四角的位置做<b>双线性插值</b>得到它自己的 3D 位置（视图空间，
 *       由顶点自带）⇒ {@link #offer} 进锚点表。插值在<b>已变换</b>的角点上做是对的：模型→视图是仿射变换
 *       （只有旋转/平移/缩放，没有透视除法），先插值再变换 == 先变换再插值；</li>
 *   <li><b>该面一个通过点都没有 ⇒ 这个面不产生任何锚点</b>（耳朵的空白区因此完全不再挂弧）；</li>
 *   <li>密度是<b>按纹素面积</b>自适应的（{@code n ≈ √(纹素面积)/4}），所以"每单位画出来的贴图面积"
 *       拿到的锚点数在各面之间基本一致 —— 不会因为某个面小就把它刷满，也不会因为平板大就把它铺满。</li>
 * </ol>
 *
 * <p><b>逐层判据</b>：同一个实体的本体层 / 发光覆层 / 盔甲层各有各的贴图，
 * 每层的 UV 只属于它自己那张图。所以"该用哪张 alpha 位图"必须<b>逐层</b>决定：
 * {@link LockOutlineLayerTexture#resolve}（反射按字段类型 → {@code toString} 正则 → 失败返回 null）
 * + {@link LockOutlineAlphaMask#get}。为此 {@link Tap#forType} 按 {@link RenderType} 身份
 * 派生"该层专属"的接收器（<b>不是</b>共用一个可变绑定 —— 那样两层交叉写顶点时判据会串味）。
 * 这两步在稳态下都只是哈希查表，零分配。</p>
 *
 * <h2>兜底（一律"退回现状"= 逐顶点取锚点，绝不因为资源问题变成不出弧）</h2>
 * <ul>
 *   <li><b>层</b>级（该层整层不过滤）：贴图解析不到 / 拿不到 CPU 像素（资源里没有这张 PNG：
 *       动态生成贴图、运行时图集、解码失败）/ 该层不是 {@code QUADS} / 逐层接收器池满
 *       ⇒ 逐顶点 {@link #offer}，与改造前<b>逐字节一致</b>（连"非 4 倍数顶点"这种异常几何也一样）；</li>
 *   <li><b>面</b>级（只这一个面退回）：4 个顶点里有顶点没收到 UV / UV 越界（该层 UV 不是归一化 UV）/
 *       UV 被旋转或斜切（同侧的顶点 UV 不相等 ⇒ 角点双线性不成立）
 *       ⇒ 这个面的 4 个顶点逐个 {@link #offer}（= 现状），其余面照旧过滤；</li>
 *   <li><b>退化面</b>（零厚度平板的棱面：UV 也退化成一维，例如监守者耳朵的 DOWN/UP/WEST/EAST 四个面）
 *       <b>不</b>按"退回现状"处理，而是<b>沿那条 UV 线采样</b>：
 *       线全透明 ⇒ 0 锚点；线有画 ⇒ 把通过点按线性插值放到对应位置。
 *       理由有实测：耳朵那 4 个退化面的 UV 线在 {@code warden.png} 里<b>全是透明</b>，
 *       若按字面"退回现状"，这块 1×1 格平板的四条边上会留下 16 个锚点 —— 又会看到"耳朵上的弧"，
 *       正是本次要消灭的现象。退化面的采样密度同样按"非退化那一维的纹素长度"自适应。</li>
 * </ul>
 * <p>每一次"退回"都记在 {@link #filterReasons()}（位掩码）里，由 {@link ShockArcs#logDraw}
 * 打进限流日志（{@code 过滤=… 未过滤面=…}），并在<b>本会话第一次</b>出现某种原因时打一条
 * {@code [ShockArc] 围弧锚点采样退回未过滤模式…}（判据是整数位比较，稳态零分配、零字符串拼接）。</p>
 *
 * <h2>采样：固定容量 + 步长自适应（写满就隔点抽稀、步长翻倍）</h2>
 * <p>目标是把"几千个模型顶点（现在是几百~几千个通过判据的采样点）"压成 64~256 个<b>铺满整个模型</b>
 * 的锚点，且不吃逐帧分配：</p>
 * <ol>
 *   <li><b>容量固定</b>：{@link #CAPACITY} 个点，{@code float[3×CAPACITY]} 一次分配、跨帧复用
 *       （每条实体一份，实体效果结束后随 {@code Track} 一起回收）；</li>
 *   <li><b>步长自适应</b>：初始步长 1（每个通过判据的采样点都算候选）；表写满时执行一次
 *       {@link #compact()}（保留偶数下标 = 隔点抽稀）并把步长翻倍。
 *       于是无论模型是 600 个顶点还是 20000 个顶点，最终表里都稳定落在
 *       {@code [CAPACITY/2, CAPACITY]} 这个区间；抽稀的相位逐轮错开，
 *       不会出现"永远只采到每个面片的同一个角"那种规律性聚集；</li>
 *   <li><b>下标稳定</b>：同一个模型（顶点数与写出顺序不变）逐帧算出来的表内容顺序完全一致，
 *       所以 {@link ShockArcs} 里"弧 = 某两个锚点下标 + 偏移"这种记法跨帧指向同一个身体部位
 *       —— 这是"弧粘在身体上跟着动"而不是"逐帧乱跳"的前提。</li>
 * </ol>
 *
 * <h2>开销（逐帧零分配，这是硬要求）</h2>
 * <ul>
 *   <li><b>每个顶点</b>：3 次 {@code double} 写 + 1 次 {@code float}×2 写（面累积）+ 1 次
 *       {@code boolean} 写；</li>
 *   <li><b>每个面</b>（只在第 4 个顶点）：4 次 min/max 扫描、6 组"同侧必须同 UV"比较、
 *       {@code n² ≤ 16} 次 alpha 查表（一次 {@code byte[]} 读 + 一次比较）、
 *       通过点各一次双线性插值（≤4 组乘加/轴）与 3 次 {@code float} 写；</li>
 *   <li><b>每层每次 getBuffer</b>：1 次 {@code WeakHashMap} 查（逐层贴图）+ 1 次
 *       "资源管理器身份比较 + HashMap 查"（alpha 位图）—— 都不分配；</li>
 *   <li><b>没有任何 {@code new}/装箱/String 拼接在热路径上</b>：面的累积数组、分组求和数组、
 *       层 Tap 池全部是<b>字段</b>（随 {@code Track}/Tab 建一次）；采样点直接写进
 *       {@link #CAPACITY} 容量的 {@code float[]}；日志门是整数位运算（见 {@link #noteUnfiltered}）。</li>
 *   <li>实测口径（离线用真 {@code warden.png} 跑同一套算法）：监守者 10 个立方体 60 个面里
 *       只有 30 个面"有画"，耳朵那块平板从 24 个顶点锚点降到 <b>2 个</b>（且都在画出来的纹理上），
 *       两个肋甲平板从 24 降到 2 —— 总的候选点仍在几百量级，抽稀后表长照旧 80~160。</li>
 * </ul>
 *
 * <h2>怎么拿到顶点</h2>
 * <p>{@link Tap} 是一个"只看不写"的 {@link VertexConsumer}：它被挂在
 * {@code ModelMirrorBufferSource#getBuffer} 返回的多路复用器上，与主缓冲、各镜像层副本并列。
 * 模型写出顶点时它会收到<b>与主缓冲逐字节相同</b>的最终坐标（{@code ModelPart} 走的是 14 参
 * {@code vertex(...)} 快路径，接口默认实现会拆成 {@code vertex(double,double,double)} +
 * 各属性 setter（顺序：color → uv → overlayCoords → uv2 → normal → endVertex，
 * 见 {@code VertexConsumer.java:32-40}），所以这里只需接住前者、把 {@code uv} 存下来，
 * 在 {@code endVertex} 里攒面）。它<b>不写任何缓冲</b>，因此不会让镜像通道付任何代价，
 * 也不会与火焰层争抢缓冲。</p>
 *
 * <p><b>硬约束</b>：本类不碰任何 GL 状态 —— 不调 {@code RenderSystem}、不设采样器、不改混合/深度/
 * 渲染目标、不碰任何着色器 JSON。读贴图 alpha 走的是
 * {@code ResourceManager → Resource#open() → NativeImage#read} 这条<b>纯 CPU</b>路径
 * （{@link LockOutlineAlphaMask} 里已有、且已被描边镂空验证过的那条）。</p>
 *
 * <p><b>线程</b>：只在客户端主线程（= 渲染线程，原版两者同线程）访问，故不加锁。</p>
 */
public final class ShockAnchors {

    private static final Logger LOGGER = LoggerFactory.getLogger("YizShockArc");

    /**
     * 锚点表容量：模型越大越可能触发抽稀，但表长恒定不超过它。
     * 160 对"玩家 604 顶点"意味着约 1/4 的顶点被采到（几乎逐面片取一个对角点，铺得很匀）；
     * 对 GeckoLib 那种上万顶点的模型则自动降到 1/16、1/32…，始终 ≈80~160 个点。
     */
    public static final int CAPACITY = 160;

    // ══════════════════════════ 过滤参数（改判据只改这里） ══════════════════════════

    /** 单面采样网格下限：{@code 2×2}（极小的面也要有 4 个采样点，否则等于没采样）。 */
    private static final int GRID_MIN = 2;
    /** 单面采样网格上限：{@code 4×4 = 16} 次 alpha 查表 —— 这是"每面最多 16 次"这条预算的来源。 */
    private static final int GRID_MAX = 4;
    /** 主 Tap 最多派生几个"逐层 Tap"（按 RenderType 身份缓存）；超了走永不绑定的兜底 Tap（= 不过滤）。 */
    private static final int POOL_MAX = 16;
    /** UV 轴对齐容差：远小于一个纹素在 UV 上的宽度，又远大于 float 误差（与描边同一套判据）。 */
    private static final float UV_AXIS_EPS = 1.0E-4F;
    /** 某一维的纹素跨度小于它就认为该维退化（零厚度薄片的棱面：UV 退化成一条线）。 */
    private static final float DEGENERATE_TEXELS = 0.5F;

    /** 未过滤原因位：该层贴图解析不到（{@link LockOutlineLayerTexture#resolve} 返回 null）。 */
    public static final int REASON_NO_TEXTURE = 1;
    /** 未过滤原因位：拿不到 CPU 侧 alpha 位图（资源里没有这张 PNG / 解码失败）。 */
    public static final int REASON_NO_BITMAP = 2;
    /** 未过滤原因位：该层的顶点模式不是 {@code QUADS}（攒不出"面"）。 */
    public static final int REASON_NOT_QUADS = 4;
    /** 未过滤原因位：面级 —— 顶点缺 UV / UV 越界 / UV 被旋转或斜切（角点双线性不成立）。 */
    public static final int REASON_ODD_FACE = 8;
    /** 未过滤原因位：逐层接收器池满（该实体这一帧的 RenderType 太多，超出的是新实例）。 */
    public static final int REASON_POOL_FULL = 16;

    // ── 形状摘要（质心 + 半径）——只有 {@link ShockArcs} 在"生成新弧"时才要，故惰性算 ──
    private float cx, cy, cz;
    /** 到质心的最大距离（≈ 模型的"半径"），用于按屏幕覆盖率自适应弧的数量。 */
    private float radius;
    private boolean summaryReady;

    // ── 逐帧过滤统计（全部 int；诊断日志用，零分配）──

    /** 本帧处理过的面数（= 每 4 个顶点一个 QUADS 面，跨该实体的所有层累加）。 */
    private int statFaces;
    /** 其中"至少有一个采样点通过 alpha 判据"的面数 —— 只有这些面会产生锚点。 */
    private int statPaintedFaces;
    /** 其中"一个通过点都没有"的面数 —— 这些面一个锚点都不产生（耳朵的空白区就在这一类）。 */
    private int statBlankFaces;
    /** 其中"因面级判据不成立而退回逐顶点"的面数（UV 越界 / 旋转斜切 / 缺 UV）。 */
    private int statUnfilteredFaces;
    /** 本帧通过 alpha 判据的采样点总数（= 喂给抽稀的候选点数，最终表长 ≤ {@link #CAPACITY}）。 */
    private int statOffers;
    /** 本帧出现过的"未过滤"原因位掩码（层级的 + 面级的）。 */
    private int statReasons;

    private final float[] xs = new float[CAPACITY];
    private final float[] ys = new float[CAPACITY];
    private final float[] zs = new float[CAPACITY];

    /** 本帧已采到的点数（≤ {@link #CAPACITY}）。 */
    private int count;
    /** 当前抽样步长：每 {@code stride} 个顶点取 1 个；每次抽稀翻倍。 */
    private int stride = 1;
    /** 本帧经过的候选点序号（只用于取模判定，不参与几何）。 */
    private int seen;

    /** 逐帧开始：清空表 + 清空统计（容量与数组都复用，零分配）。 */
    public void beginFrame() {
        count = 0;
        seen = 0;
        stride = 1;
        summaryReady = false;
        statFaces = 0;
        statPaintedFaces = 0;
        statBlankFaces = 0;
        statUnfilteredFaces = 0;
        statOffers = 0;
        statReasons = 0;
    }

    /** 收一个通过判据的锚点候选（<b>视图空间</b>坐标，已经是最终位置：视图旋转 / 相机相对平移 / 姿态全部烤好了）。 */
    public void offer(double x, double y, double z) {
        statOffers++;
        if ((seen++ % stride) != 0) return;
        if (count == CAPACITY) compact();
        xs[count] = (float) x;
        ys[count] = (float) y;
        zs[count] = (float) z;
        count++;
    }

    /**
     * 表写满时的抽稀：保留偶数下标（= 采样序列里隔一个留一个），步长翻倍。
     * 代价 O(CAPACITY/2)，一帧最多发生 log2(候选点数/CAPACITY) 次。
     */
    private void compact() {
        int n = 0;
        for (int i = 0; i < CAPACITY; i += 2) {
            xs[n] = xs[i];
            ys[n] = ys[i];
            zs[n] = zs[i];
            n++;
        }
        count = n;
        stride *= 2;
    }

    /** 本帧采到的锚点数。 */
    public int count() {
        return count;
    }

    public float x(int i) { return xs[i]; }
    public float y(int i) { return ys[i]; }
    public float z(int i) { return zs[i]; }

    // ── 过滤统计（诊断日志；读的都是本帧的值）──

    /** 本帧处理过的面数。 */
    public int statFaces() { return statFaces; }
    /** 本帧"有画"（至少一个采样点通过 alpha）的面数。 */
    public int statPaintedFaces() { return statPaintedFaces; }
    /** 本帧"一个通过点都没有"的面数（这些面不产生锚点）。 */
    public int statBlankFaces() { return statBlankFaces; }
    /** 本帧因面级判据不成立而退回逐顶点的面数。 */
    public int statUnfilteredFaces() { return statUnfilteredFaces; }
    /** 本帧通过 alpha 判据的候选点总数（抽稀之前）。 */
    public int statOffers() { return statOffers; }
    /** 本帧出现过的未过滤原因位掩码（0 = 该实体本帧所有层都按贴图 alpha 过滤）。 */
    public int filterReasons() { return statReasons; }

    /**
     * 惰性计算"质心 + 半径"。只在 {@link ShockArcs#tick()} 里生成新弧时调一次，
     * 复杂度 O(count)（≤160 次浮点乘加），20 Hz 一次。
     */
    public void ensureSummary() {
        if (summaryReady) return;
        summaryReady = true;
        if (count == 0) {
            cx = cy = cz = 0f;
            radius = 0f;
            return;
        }
        float sx = 0f, sy = 0f, sz = 0f;
        for (int i = 0; i < count; i++) {
            sx += xs[i];
            sy += ys[i];
            sz += zs[i];
        }
        cx = sx / count;
        cy = sy / count;
        cz = sz / count;
        float r2 = 0f;
        for (int i = 0; i < count; i++) {
            float dx = xs[i] - cx, dy = ys[i] - cy, dz = zs[i] - cz;
            float d2 = dx * dx + dy * dy + dz * dz;
            if (d2 > r2) r2 = d2;
        }
        radius = (float) Math.sqrt(r2);
    }

    public float centroidX() { return cx; }
    public float centroidY() { return cy; }
    public float centroidZ() { return cz; }

    /** 质心到相机的距离（视图空间里相机就在原点）。 */
    public float centroidDistance() {
        return (float) Math.sqrt(cx * cx + cy * cy + cz * cz);
    }

    /** 模型半径（到质心的最大距离）。 */
    public float radius() { return radius; }

    // ══════════════════════════ 未过滤原因的可读文案 + 会话级限流日志 ══════════════════════════

    /** 本会话已经打过日志的原因位（位比较 = 零分配、零字符串拼接的热路径门）。 */
    private static int loggedReasons;

    /**
     * "某种原因第一次出现"时打一条说明（每个原因位整场会话只打一次）。
     *
     * <p>为什么不用 {@code MirrorDiag.first("…" + tex)}：那需要<b>每次调用</b>拼一个字符串 key
     * （本方法在"该层不过滤"时每次 getBuffer 都会被调到），热路径上不能这么干。
     * 这里先做一次整数位运算，只有真的要打日志时才去碰 {@code toString()}。</p>
     */
    static void noteUnfiltered(int reason, @Nullable ResourceLocation texture, @Nullable RenderType type) {
        int fresh = reason & ~loggedReasons;
        if (fresh == 0) return;
        loggedReasons |= fresh;
        LOGGER.info("[ShockArc] 围弧锚点采样退回未过滤模式（按顶点取锚点 = 改造前行为，弧照样出，只是不按贴图镂空）: "
                        + "原因={} 层={} 贴图={} —— 每个原因整场只打一次；"
                        + "锚点过滤只在「能解析出该层贴图 + 能读到它的 PNG 像素 + 该层是 QUADS」时生效",
                reasonText(fresh), type, texture);
    }

    /** 原因位掩码 → 可读文案（只用在日志上，允许分配）。 */
    public static String reasonText(int reasons) {
        if (reasons == 0) return "无";
        StringBuilder sb = new StringBuilder();
        if ((reasons & REASON_NO_TEXTURE) != 0) sb.append("贴图解析不到");
        if ((reasons & REASON_NO_BITMAP) != 0) sb.append(sb.length() == 0 ? "" : "+").append("读不到CPU像素(非资源包PNG)");
        if ((reasons & REASON_NOT_QUADS) != 0) sb.append(sb.length() == 0 ? "" : "+").append("非QUADS层");
        if ((reasons & REASON_ODD_FACE) != 0) sb.append(sb.length() == 0 ? "" : "+").append("个别面UV越界/斜切");
        if ((reasons & REASON_POOL_FULL) != 0) sb.append(sb.length() == 0 ? "" : "+").append("逐层接收器池满");
        return sb.length() == 0 ? "无" : sb.toString();
    }

    /**
     * "只看不写"的顶点接收器：接住模型写出的顶点，<b>攒成面</b>后按该层贴图的 alpha 过滤，
     * 只在贴图真正画到的地方产出锚点。
     *
     * <p>为什么继承 {@link DefaultedVertexConsumer}：它只提供"默认顶点色"那几个字段、
     * 不持有任何 delegate，所以这里不实现 {@code defaultColor/unsetDefaultColor} 也不会 NPE
     * （同一个基类也是 {@code MirrorChannel.MirrorCopyConsumer} 用的）。</p>
     *
     * <p>为什么不实现 14 参的 {@code vertex(...)}：{@code VertexConsumer} 的默认实现会把它拆成
     * {@code vertex(double,double,double)} + 各属性 setter（顺序见 {@code VertexConsumer.java:32-40}，
     * {@code uv} 在 {@code endVertex} <b>之前</b>）—— 正好是本类唯一关心的那两步，
     * 而且 {@code ModelPart.Cube#compile} 走的正是这条 14 参快路径（位置在那里已经乘过姿态矩阵，
     * UV 是图集归一化 UV）。</p>
     *
     * <p><b>一个主 Tap + 一池"逐层 Tap"</b>：{@link #primary} 造出来的主 Tap 不接顶点，
     * 只当"本帧这个实体要收锚点"的标记与工厂；{@link #forType} 按 {@link RenderType} 身份
     * 返回该层专属的接收器（绑定该层自己的贴图 alpha 位图）。这样即便某个渲染器把两层的
     * 消费者<b>交叉</b>着写顶点，判据也不会串味；池满（异常多的 RenderType 实例）则返回一个
     * 永不绑定的兜底接收器（= 该层不过滤，行为退回改造前）。</p>
     */
    public static final class Tap extends DefaultedVertexConsumer {

        // ── 逐面采样模式 ──
        /** 四个角色齐备 ⇒ 角点双线性插值。 */
        private static final int MODE_BILINEAR = 1;
        /** UV 在 v 上退化（一条水平线）⇒ 沿 u 线性插值。 */
        private static final int MODE_LINE_U = 2;
        /** UV 在 u 上退化（一条竖直线）⇒ 沿 v 线性插值。 */
        private static final int MODE_LINE_V = 3;
        /** UV 退化成一个点 ⇒ 四点取平均，只产出一个锚点。 */
        private static final int MODE_POINT = 4;

        private final ShockAnchors anchors;

        /** 主 Tap 的"逐层 Tap"池（{@link RenderType} 是 memoize 出来的单例 ⇒ 身份比较够用）；层 Tap 为 null。 */
        @Nullable
        private final Map<RenderType, Tap> pool;
        /** 池满之后的共用兜底接收器：永不绑定贴图 ⇒ 该层按顶点取锚点（= 改造前）。 */
        @Nullable
        private Tap overflow;

        // ── 本层绑定（每次 getBuffer 刷新；两个字段都是引用/布尔，零分配）──
        /** 本层贴图的 alpha 位图；{@code null} = 本层不过滤（逐顶点 offer，与改造前一致）。 */
        @Nullable
        private LockOutlineAlphaMask mask;

        // ── 当前顶点暂存 ──
        private double vx, vy, vz;
        private float vu, vv;
        private boolean vUv;

        // ── 一个面的 4 个顶点（QUADS：每 4 次 endVertex 恰好一个面）──
        private final double[] px = new double[4];
        private final double[] py = new double[4];
        private final double[] pz = new double[4];
        private final float[] pu = new float[4];
        private final float[] pv = new float[4];
        private int faceCount;
        /** 本面有顶点没收到 uv（非 NEW_ENTITY 语义 / 异常几何）⇒ 这个面退回逐顶点。 */
        private boolean faceUvMissing;

        // ── 角点分组缓存（每面复用，零分配）──
        /** 每个角色（bit0 = u 取大端、bit1 = v 取大端）的位置和与计数。 */
        private final double[] sumX = new double[4];
        private final double[] sumY = new double[4];
        private final double[] sumZ = new double[4];
        private final int[] groupN = new int[4];
        /** 每个角色的平均位置（同组顶点在正确的轴对齐 UV 矩形里坐标相同；取平均对重复顶点也稳妥）。 */
        private final float[] ax = new float[4];
        private final float[] ay = new float[4];
        private final float[] az = new float[4];
        /** 每个顶点的 u/v 侧（0 = 小端、1 = 大端），"同侧必须同 UV"的严格性检查用。 */
        private final int[] uSide = new int[4];
        private final int[] vSide = new int[4];

        /** 主 Tap：不接顶点，只当标记 + 逐层 Tap 工厂（见 {@link #forType}）。 */
        public static Tap primary(ShockAnchors anchors) {
            return new Tap(anchors, new IdentityHashMap<>());
        }

        private Tap(ShockAnchors anchors, @Nullable Map<RenderType, Tap> pool) {
            this.anchors = anchors;
            this.pool = pool;
        }

        /**
         * 取<b>该层专属</b>的接收器（按 {@link RenderType} 身份缓存，池上限 {@link #POOL_MAX}）。
         *
         * <p>每次调用都会重新 {@link #bind} 一次：稳态下就是"1 次 WeakHashMap 查 + 1 次
         * 资源管理器身份比较 + 1 次 HashMap 查"，不分配；好处是资源重载（换 ResourceManager）
         * 后自带失效重试，不需要任何监听器。</p>
         */
        public Tap forType(@Nullable RenderType type) {
            Map<RenderType, Tap> p = this.pool;
            if (p == null || type == null) return this;      // 层 Tap / 兜底 Tap / type 为 null：直接用自己
            Tap t = p.get(type);
            if (t == null) {
                if (p.size() >= POOL_MAX) {
                    this.anchors.statReasons |= REASON_POOL_FULL;
                    Tap fb = this.overflow;
                    if (fb == null) {
                        fb = new Tap(this.anchors, null);
                        this.overflow = fb;
                        noteUnfiltered(REASON_POOL_FULL, null, type);
                    }
                    return fb;                                // 永不 bind ⇒ 该层不过滤
                }
                t = new Tap(this.anchors, null);
                p.put(type, t);
            }
            t.bind(type);
            return t;
        }

        /**
         * 绑定"本层贴图"的 alpha 位图。三层判据（贴图解析到 + 位图可用 + 该层是 QUADS）全过才过滤，
         * 否则逐顶点 offer（= 改造前行为）并记下原因。
         */
        private void bind(RenderType type) {
            ResourceLocation tex = LockOutlineLayerTexture.resolve(type);
            LockOutlineAlphaMask m = tex == null ? null : LockOutlineAlphaMask.get(tex);
            boolean filtered = m != null && m.available() && type.mode() == VertexFormat.Mode.QUADS;
            LockOutlineAlphaMask next = filtered ? m : null;
            // 判据模式变了（正常路径每帧都是"过滤→过滤"，这一句是空操作）⇒ 丢掉可能残留的半个面。
            // 合法 QUADS 几何永远是 4 的倍数（ModelPart.Cube 逐 Polygon 写 4 个顶点），正常路径残留恒为 0。
            if (next != this.mask) this.faceCount = 0;
            this.mask = next;
            if (filtered) return;
            int reason = tex == null ? REASON_NO_TEXTURE
                    : (m == null || !m.available()) ? REASON_NO_BITMAP : REASON_NOT_QUADS;
            this.anchors.statReasons |= reason;
            noteUnfiltered(reason, tex, type);
        }

        @Override
        public VertexConsumer vertex(double x, double y, double z) {
            this.vx = x;
            this.vy = y;
            this.vz = z;
            this.vUv = false;      // 新顶点开始：uv() 是否来过由它自己决定
            return this;
        }

        @Override
        public VertexConsumer color(int r, int g, int b, int a) {
            return this;   // 锚点只关心位置与 UV
        }

        @Override
        public VertexConsumer uv(float u, float v) {
            this.vu = u;
            this.vv = v;
            this.vUv = true;
            return this;
        }

        @Override
        public VertexConsumer overlayCoords(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer uv2(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer normal(float x, float y, float z) {
            return this;
        }

        @Override
        public void endVertex() {
            // 未过滤（层次级兜底 / 非 QUADS）：<b>逐顶点</b> offer —— 与改造前逐字节一致，
            // 包括"非 4 倍数顶点"这种异常几何（不攒面就永远不会丢零头）。
            if (this.mask == null) {
                this.anchors.offer(this.vx, this.vy, this.vz);
                return;
            }
            // 过滤模式：攒面（走到这里必然是 QUADS，见 bind）
            int i = this.faceCount;
            if (i == 0) this.faceUvMissing = false;
            this.px[i] = this.vx;
            this.py[i] = this.vy;
            this.pz[i] = this.vz;
            this.pu[i] = this.vu;
            this.pv[i] = this.vv;
            if (!this.vUv) this.faceUvMissing = true;
            i++;
            if (i == 4) {
                this.faceCount = 0;
                emitFace();
            } else {
                this.faceCount = i;
            }
        }

        /**
         * 一个 QUADS 面的完整判定：UV 矩形 → 网格采样 → 纹素 alpha → 双线性插值位置 → offer。
         * 任何一步判据不成立都"退回现状"（这个面的 4 个顶点逐个 offer），并计数。
         */
        private void emitFace() {
            ShockAnchors an = this.anchors;
            LockOutlineAlphaMask m = this.mask;
            an.statFaces++;
            if (m == null || this.faceUvMissing) {
                an.statUnfilteredFaces++;
                an.statReasons |= REASON_ODD_FACE;
                offerAllFour(an);
                return;
            }
            float uMin = this.pu[0], uMax = this.pu[0], vMin = this.pv[0], vMax = this.pv[0];
            for (int i = 1; i < 4; i++) {
                if (this.pu[i] < uMin) uMin = this.pu[i];
                if (this.pu[i] > uMax) uMax = this.pu[i];
                if (this.pv[i] < vMin) vMin = this.pv[i];
                if (this.pv[i] > vMax) vMax = this.pv[i];
            }
            // UV 越界 ⇒ 这一层的 UV 不是归一化 UV（例如某个 MOD 直接写像素坐标）⇒ 这个面退回现状
            if (!m.uvInRange(uMin, vMin, uMax, vMax)) {
                an.statUnfilteredFaces++;
                an.statReasons |= REASON_ODD_FACE;
                offerAllFour(an);
                return;
            }
            // ── 角点分角色（bit0 = u 取大端、bit1 = v 取大端），顺带做"同侧必须同 UV"的严格性检查 ──
            float um = (uMin + uMax) * 0.5f;
            float vm = (vMin + vMax) * 0.5f;
            for (int i = 0; i < 4; i++) {
                this.sumX[i] = 0.0;
                this.sumY[i] = 0.0;
                this.sumZ[i] = 0.0;
                this.groupN[i] = 0;
            }
            for (int i = 0; i < 4; i++) {
                int us = this.pu[i] > um ? 1 : 0;
                int vs = this.pv[i] > vm ? 1 : 0;
                this.uSide[i] = us;
                this.vSide[i] = vs;
                int r = us | (vs << 1);
                this.sumX[r] += this.px[i];
                this.sumY[r] += this.py[i];
                this.sumZ[r] += this.pz[i];
                this.groupN[r]++;
            }
            // 同一侧的顶点 UV 必须相等，否则 UV 被旋转/斜切过，"角点双线性"不再等于真实的 UV→位置映射
            // （判据与 LockOutlineFillConsumer#roles 一致；标准实体模型的轴对齐 UV 矩形永远满足）
            for (int i = 0; i < 4; i++) {
                for (int j = i + 1; j < 4; j++) {
                    if (this.uSide[i] == this.uSide[j] && Math.abs(this.pu[i] - this.pu[j]) > UV_AXIS_EPS) {
                        an.statUnfilteredFaces++;
                        an.statReasons |= REASON_ODD_FACE;
                        offerAllFour(an);
                        return;
                    }
                    if (this.vSide[i] == this.vSide[j] && Math.abs(this.pv[i] - this.pv[j]) > UV_AXIS_EPS) {
                        an.statUnfilteredFaces++;
                        an.statReasons |= REASON_ODD_FACE;
                        offerAllFour(an);
                        return;
                    }
                }
            }
            // ── 纹素跨度 / 退化判定 / 采样模式 ──
            float uw = (uMax - uMin) * m.width();
            float vh = (vMax - vMin) * m.height();
            boolean degenU = uw < DEGENERATE_TEXELS;
            boolean degenV = vh < DEGENERATE_TEXELS;
            int bits = (this.groupN[0] > 0 ? 1 : 0) | (this.groupN[1] > 0 ? 2 : 0)
                    | (this.groupN[2] > 0 ? 4 : 0) | (this.groupN[3] > 0 ? 8 : 0);
            int mode;
            if (bits == 15) {
                mode = MODE_BILINEAR;
            } else if (bits == 3 && degenV) {
                mode = MODE_LINE_U;          // 只有"u 小端/大端"两组 ⇒ 一条水平 UV 线（零厚度平板的上下棱）
            } else if (bits == 5 && degenU) {
                mode = MODE_LINE_V;          // 一条竖直 UV 线（左右棱）
            } else if (bits == 1 && degenU && degenV) {
                mode = MODE_POINT;           // UV 退化成一个点
            } else {
                an.statUnfilteredFaces++;
                an.statReasons |= REASON_ODD_FACE;
                offerAllFour(an);            // 非常规四边形 ⇒ 退回现状
                return;
            }
            for (int r = 0; r < 4; r++) {
                int n = this.groupN[r];
                if (n == 0) continue;
                this.ax[r] = (float) (this.sumX[r] / n);
                this.ay[r] = (float) (this.sumY[r] / n);
                this.az[r] = (float) (this.sumZ[r] / n);
            }
            int hits = 0;
            if (mode == MODE_POINT) {
                // 退化成一个点：只查一次、只产一个锚点（否则 n×n 会产出 4 个完全重合的点）
                if (m.opaqueTexel(texel(uMin, m.width()), texel(vMin, m.height()))) {
                    an.offer(this.ax[0], this.ay[0], this.az[0]);
                    hits = 1;
                }
            } else {
                // 采样密度按"面在贴图上覆盖的纹素面积开方 / 4"自适应；退化面按非退化那一维的长度。
                // 16×16 纹素的面 ⇒ 4×4 = 16 个点（每点约 16 纹素）；8×8 ⇒ 2×2；上限恒为 16 次查表。
                float span = degenU ? vh : (degenV ? uw : (float) Math.sqrt(uw * vh));
                int n = Math.round(span * 0.25f);
                if (n < GRID_MIN) n = GRID_MIN;
                else if (n > GRID_MAX) n = GRID_MAX;
                int wt = m.width();
                int ht = m.height();
                float du = uMax - uMin;
                float dv = vMax - vMin;
                for (int i = 0; i < n; i++) {
                    float s = (i + 0.5f) / n;
                    int tx = texel(uMin + s * du, wt);          // 外层的纹素下标提到内层循环外
                    for (int j = 0; j < n; j++) {
                        float t = (j + 0.5f) / n;
                        if (!m.opaqueTexel(tx, texel(vMin + t * dv, ht))) continue;
                        offerSample(an, mode, s, t);            // 通过判据 ⇒ 双线性插值出位置
                        hits++;
                    }
                }
            }
            if (hits > 0) {
                an.statPaintedFaces++;       // 这个面"有画"⇒ 已经按通过点产出锚点
            } else {
                an.statBlankFaces++;         // ★ 这个面一个通过点都没有 ⇒ 一个锚点都不产生（耳朵的空白区）
            }
        }

        /**
         * 把通过 alpha 判据的采样点 (s,t)（相对本面 UV 矩形的 0~1 参数）换算成 3D 位置并 offer。
         *
         * <p>三种模式共用同一套"角点平均位置"：{@code ax/ay/az[r]}，{@code r} 的 bit0 = u 大端、
         * bit1 = v 大端。所以：</p>
         * <ul>
         *   <li>{@link #MODE_BILINEAR}：四点双线性（模型面是平面矩形，双线性在 UV 与位置之间是精确的）；</li>
         *   <li>{@link #MODE_LINE_U}：v 退化 ⇒ 沿 u 在"u 小端组 / u 大端组"的平均位置之间线性插值；</li>
         *   <li>{@link #MODE_LINE_V}：u 退化 ⇒ 沿 v 同理。</li>
         * </ul>
         */
        private void offerSample(ShockAnchors an, int mode, float s, float t) {
            if (mode == MODE_BILINEAR) {
                float w0 = (1f - s) * (1f - t);
                float w1 = s * (1f - t);
                float w2 = (1f - s) * t;
                float w3 = s * t;
                an.offer(w0 * this.ax[0] + w1 * this.ax[1] + w2 * this.ax[2] + w3 * this.ax[3],
                        w0 * this.ay[0] + w1 * this.ay[1] + w2 * this.ay[2] + w3 * this.ay[3],
                        w0 * this.az[0] + w1 * this.az[1] + w2 * this.az[2] + w3 * this.az[3]);
            } else if (mode == MODE_LINE_U) {
                an.offer(this.ax[0] + (this.ax[1] - this.ax[0]) * s,
                        this.ay[0] + (this.ay[1] - this.ay[0]) * s,
                        this.az[0] + (this.az[1] - this.az[0]) * s);
            } else {
                an.offer(this.ax[0] + (this.ax[2] - this.ax[0]) * t,
                        this.ay[0] + (this.ay[2] - this.ay[0]) * t,
                        this.az[0] + (this.az[2] - this.az[0]) * t);
            }
        }

        /** 面级兜底：这个面的 4 个顶点逐个 offer（= 改造前的行为，一字不差）。 */
        private void offerAllFour(ShockAnchors an) {
            for (int i = 0; i < 4; i++) {
                an.offer(this.px[i], this.py[i], this.pz[i]);
            }
        }

        /** UV → 纹素下标（夹在 [0, 尺寸-1]；调用方已用 {@code uvInRange} 挡过越界）。 */
        private static int texel(float uv, int size) {
            int t = (int) (uv * size);
            return t < 0 ? 0 : (t >= size ? size - 1 : t);
        }
    }
}
