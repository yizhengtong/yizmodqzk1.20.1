package net.minecraft.client.yiz.client.render;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.yiz.modelmirror.MirrorDiag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * <b>一张实体贴图的 CPU 侧 alpha 位图</b> —— "按贴图 alpha 镂空"的唯一判据来源。
 *
 * <h2>它解决什么</h2>
 * <p>描边填充（{@code rendertype_lock_fill}）此前是<b>实心几何掩码</b>：整个四边形的面积都填进描边 FBO，
 * 于是"贴图里根本没有像素的部位"（零厚度薄片的背面、只有细丝图案的薄片）也被整体描了一圈。
 * 本类把贴图的 alpha 读到 CPU，让填充消费者在写顶点时就能判断
 * "这个面／这条纹素连通段到底有没有可见像素"。</p>
 *
 * <h2>alpha 从哪来（为什么是安全的纯 CPU 路径）</h2>
 * <ol>
 *   <li>贴图 {@link ResourceLocation} → {@code Minecraft#getResourceManager()#getResource(rl)}
 *       —— 1.20.1 的 {@code ResourceManager#getResource} 返回 {@code Optional<Resource>}，
 *       CLIENT_RESOURCES（{@code assets/}）前缀由资源包层内部处理；</li>
 *   <li>{@code Resource#open()} → {@link NativeImage#read(InputStream)}：STB 解码 PNG，
 *       <b>纯 CPU + 本地内存</b>（不碰任何 GL 对象、不碰 {@code RenderSystem} 的任何状态）；</li>
 *   <li>逐纹素取 alpha：{@code getPixelsRGBA()} 一次性拿到 {@code int[]}，
 *       alpha 在 <b>最高字节</b>（= {@code FastColor.ABGR32.alpha()} 与
 *       {@code NativeImage.Format.RGBA.alphaOffset=24} 两条独立佐证），即 {@code (px >>> 24) & 0xFF}；</li>
 *   <li>{@link NativeImage#close()} 释放本地内存后只留一份 {@code byte[]}（每纹素 1 字节），
 *       按贴图缓存。</li>
 * </ol>
 * <p>拿不到 CPU 像素的情况（资源包里没有这张 PNG：动态生成/网络下载的皮肤、方块图集
 * {@code InventoryMenu.BLOCK_ATLAS} 这类运行时拼接的图集、读图抛异常）⇒ 返回
 * {@link #UNAVAILABLE}，调用方<b>退回现状</b>（不做 CPU 镂空，整面照填）。</p>
 *
 * <h2>缓存与性能</h2>
 * <ul>
 *   <li>位图按 {@link ResourceLocation} 缓存；资源重载时 {@code Minecraft#getResourceManager()}
 *       会换成新的 ResourceManager 实例 ⇒ 这里用<b>身份比较</b>即可失效（不需要注册重载监听）；</li>
 *   <li>「纹素包围盒 → 不透明连通段矩形」的分解结果按包围盒缓存（开地址线性探测，
 *       <b>不装箱、不每帧分配</b>），因此稳态下每个面只有一次查表；</li>
 *   <li>分解只在缓存未命中时做一次，且天然有界：包围盒最大就是这张贴图的尺寸。</li>
 * </ul>
 *
 * <h2>现在它也是「围弧（编号 4）锚点按贴图 alpha 过滤」的公共判据（2026-10-05 起）</h2>
 * <p>该功能的判据与描边镂空<b>同源</b>：同样是"这一层贴图 + 这个面的 UV 矩形 → 纹素 alpha"。
 * 为此本类补了三个<b>只读、零分配</b>的公共入口（都是既有语义的暴露，不改动任何既有算法）：</p>
 * <ul>
 *   <li>{@link #opaqueTexel(int, int)}：单个纹素是否不透明（阈值同一个 {@link #ALPHA_CUTOFF}，
 *       即 fsh 里那句 {@code tex.a < 0.1} 的 CPU 等价物）；</li>
 *   <li>{@link #uvInRange(float, float, float, float)}：UV 矩形是否落在 [0,1] 内
 *       （原 {@link #texelBox} 的前置条件，独立出来给"越界就退回现状"的调用方用）；</li>
 *   <li>{@link #width()} / {@link #height()}：UV ↔ 纹素换算要用（本来就有）。</li>
 * </ul>
 * <p>调用方（{@code ShockAnchors.Tap}）只做"采样点 → 纹素下标 → 查表"，
 * 不复制任何像素、不碰任何 GL 状态。三层语义（条纹素/包围盒/连通段）各有各的用途：
 * 描边用连通段切子面，围弧用逐纹素点采样。</p>
 */
public final class LockOutlineAlphaMask {

    private static final Logger LOGGER = LoggerFactory.getLogger("YizModelMirror");

    /** 与 {@code rendertype_lock_fill.fsh} 的 {@code if (tex.a < 0.1) discard;} 对齐：0.1 × 255 ≈ 26。 */
    public static final int ALPHA_CUTOFF = 26;

    /** 纹素↔UV 换算容差：防止 float 误差把边界纹素算丢（方向永远偏"多留"，不会误删）。 */
    private static final float TEXEL_EPS = 1.0E-3F;

    /** 单个面最多允许的不透明连通段数；超过就整面回退（不做镂空），保证子面几何量有界。 */
    public static final int MAX_RECTS_PER_FACE = 128;

    /** 判据缓存槽数（2 的幂）；满了就覆盖首个探测槽，不增长、不分配。 */
    private static final int CACHE_SLOTS = 256;

    /** 哨兵：拿不到 alpha 位图（调用方据此退回现状）。 */
    public static final LockOutlineAlphaMask UNAVAILABLE = new LockOutlineAlphaMask(null, 0, 0, 0);

    private static final Map<ResourceLocation, LockOutlineAlphaMask> CACHE = new HashMap<>();
    /** 建立缓存时用的资源管理器（身份变了 = 资源重载过 ⇒ 全部失效）。 */
    @Nullable
    private static ResourceManager cacheOwner;

    private final byte[] alpha;
    private final int width;
    private final int height;
    private final int opaqueTexels;

    /** 判据缓存：纹素包围盒 → 分解结果（{xa,xb,ya,yb} 四元组数组；空数组 = 整面无可见纹素）。 */
    private final long[] cacheKeys = new long[CACHE_SLOTS];
    private final int[][] cacheValues = new int[CACHE_SLOTS][];
    private final boolean[] cacheUsed = new boolean[CACHE_SLOTS];

    private LockOutlineAlphaMask(@Nullable byte[] alpha, int width, int height, int opaqueTexels) {
        this.alpha = alpha;
        this.width = width;
        this.height = height;
        this.opaqueTexels = opaqueTexels;
    }

    // ══════════════════════════════ 取用 ══════════════════════════════

    /** 取（或建立）某张贴图的 alpha 位图；任何失败都返回 {@link #UNAVAILABLE}。 */
    public static LockOutlineAlphaMask get(@Nullable ResourceLocation texture) {
        if (texture == null) return UNAVAILABLE;
        ResourceManager rm = Minecraft.getInstance().getResourceManager();
        if (rm != cacheOwner) {           // 资源重载 ⇒ 旧位图全部作废
            CACHE.clear();
            cacheOwner = rm;
        }
        LockOutlineAlphaMask cached = CACHE.get(texture);
        if (cached != null) return cached;
        LockOutlineAlphaMask built = build(texture, rm);
        CACHE.put(texture, built);
        if (MirrorDiag.first("lock:mask:" + texture)) {
            if (built.available()) {
                LOGGER.info("[LockOutline] 按贴图 alpha 镂空：alpha 位图已建立 贴图={} 尺寸={}x{} "
                                + "不透明纹素={}/{} ({}) —— 这是 CPU 侧判据（fsh 那句 discard 是否生效与本项无关）",
                        texture, built.width, built.height, built.opaqueTexels, built.width * built.height,
                        String.format(java.util.Locale.ROOT, "%.1f%%", 100.0 * built.opaqueTexels / (built.width * built.height)));
            } else {
                LOGGER.info("[LockOutline] 按贴图 alpha 镂空：{} 拿不到 CPU 像素（资源里没有这张 PNG：动态生成贴图/"
                                + "运行时图集/解码失败）⇒ 该层退回现状（整面填充，不镂空）", texture);
            }
        }
        return built;
    }

    /** 手动失效（目前只被资源管理器身份变化隐式触发；留给将来接 ReloadListener 用）。 */
    public static void invalidate() {
        CACHE.clear();
        cacheOwner = null;
    }

    /** 有没有可用的 alpha 位图。 */
    public boolean available() {
        return this.alpha != null;
    }

    public int width() {
        return this.width;
    }

    public int height() {
        return this.height;
    }

    // ══════════════════════════════ 建图 ══════════════════════════════

    private static LockOutlineAlphaMask build(ResourceLocation texture, @Nullable ResourceManager rm) {
        if (rm == null) return UNAVAILABLE;
        try {
            Optional<Resource> res = rm.getResource(texture);
            if (res.isEmpty()) return UNAVAILABLE;
            try (InputStream in = res.get().open(); NativeImage image = NativeImage.read(in)) {
                if (image.format() != NativeImage.Format.RGBA) return UNAVAILABLE;
                int w = image.getWidth();
                int h = image.getHeight();
                if (w <= 0 || h <= 0 || (long) w * (long) h > (1L << 22)) return UNAVAILABLE;
                int[] pixels = image.getPixelsRGBA();
                byte[] a = new byte[w * h];
                int opaque = 0;
                for (int i = 0; i < a.length; i++) {
                    int al = (pixels[i] >>> 24) & 0xFF;   // FastColor.ABGR32.alpha（见类注释）
                    a[i] = (byte) al;
                    if (al >= ALPHA_CUTOFF) opaque++;
                }
                return new LockOutlineAlphaMask(a, w, h, opaque);
            }
        } catch (Throwable t) {
            // 兜底：宁可"不镂空"，也不让一次读图失败影响渲染
            return UNAVAILABLE;
        }
    }

    // ══════════════════════════════ 查询 ══════════════════════════════

    /**
     * 把「一个面在 UV 空间覆盖的矩形」换算成纹素包围盒（含端点），写进 {@code out[0..3] = x0,y0,x1,y1}。
     *
     * <p>换算规则：覆盖到的纹素是 {@code [floor(uMin*W), ceil(uMax*W)-1]}（y 同理），这是"该 UV 矩形
     * 与哪些纹素有面积交集"的精确集合。容差 {@link #TEXEL_EPS} 只用来吸收 float 误差，方向偏"多算一个"。</p>
     *
     * @return {@code false} 表示不能用这套判据（位图不可用 / UV 越界 / UV 矩形在某一维退化），调用方必须退回现状
     */
    public boolean texelBox(float uMin, float vMin, float uMax, float vMax, int[] out) {
        if (!this.available()) return false;
        if (!uvInRange(uMin, vMin, uMax, vMax)) {
            return false;
        }
        float uw = (uMax - uMin) * this.width;
        float vh = (vMax - vMin) * this.height;
        // 某一维退化（零厚度薄片的"侧棱面"：UV 也退化成一条线）⇒ 该面本身没有面积，不做判据
        if (uw < 0.5F || vh < 0.5F) return false;
        int x0 = clamp((int) Math.floor(uMin * this.width + TEXEL_EPS), 0, this.width - 1);
        int x1 = clamp((int) Math.ceil(uMax * this.width - TEXEL_EPS) - 1, 0, this.width - 1);
        int y0 = clamp((int) Math.floor(vMin * this.height + TEXEL_EPS), 0, this.height - 1);
        int y1 = clamp((int) Math.ceil(vMax * this.height - TEXEL_EPS) - 1, 0, this.height - 1);
        if (x0 > x1 || y0 > y1) return false;
        out[0] = x0;
        out[1] = y0;
        out[2] = x1;
        out[3] = y1;
        return true;
    }

    /**
     * UV 矩形是否落在 [0,1] 内（同一套容差 {@link #TEXEL_EPS}）。
     *
     * <p>与 {@link #texelBox} 里那道检查逐字相同，只是单独暴露出来：调用方
     * （围弧锚点采样）需要把"UV 越界"与"UV 退化"分开处理 —— 前者说明这一层的 UV 不是
     * 归一化 UV（例如某个 MOD 直接写像素坐标）⇒ 该层整个退回未过滤；后者只是零厚度薄片的棱面。</p>
     */
    public boolean uvInRange(float uMin, float vMin, float uMax, float vMax) {
        return uMin >= -TEXEL_EPS && vMin >= -TEXEL_EPS
                && uMax <= 1.0F + TEXEL_EPS && vMax <= 1.0F + TEXEL_EPS;
    }

    /**
     * <b>单个纹素</b>是否不透明（{@code alpha >= }{@link #ALPHA_CUTOFF} = 0.1×255）。
     *
     * <p>纯数组读：位图不可用 / 下标越界一律返回 {@code false}（调用方按"这里没有画"处理）。
     * 围弧锚点的 UV 网格采样走的就是这一条 —— 每次调用一次 {@code byte[]} 读 + 一次比较，
     * 不装箱、不分配、不碰 GL。</p>
     */
    public boolean opaqueTexel(int tx, int ty) {
        byte[] a = this.alpha;
        if (a == null || tx < 0 || ty < 0 || tx >= this.width || ty >= this.height) return false;
        return (a[ty * this.width + tx] & 0xFF) >= ALPHA_CUTOFF;
    }

    /**
     * 纹素包围盒内的「不透明连通段」分解：返回 {@code int[]}，每 4 个一组 {@code {xa, xb, ya, yb}}（含端点）。
     * 空数组表示这个包围盒里<b>一个可见纹素都没有</b> ⇒ 整个面被贴图判为透明。
     *
     * <p>贪心矩形分解：逐行取连续不透明段，能"同宽跨行延续"的就长高。它保证
     * ① 每个不透明纹素恰好属于一个矩形（不重不漏）；② 整面都不透明时恰好只有 1 个矩形
     * （调用方据此走"原样输出"的快路径，普通实体面的填充几何因此<b>一个顶点都不变</b>）。
     * 段数超过 {@link #MAX_RECTS_PER_FACE} 时返回 {@code null}，调用方据此整面回退。</p>
     */
    @Nullable
    public int[] rectsFor(int x0, int y0, int x1, int y1) {
        if (!this.available()) return null;
        long key = key(x0, y0, x1, y1);
        int start = index(key);
        for (int probe = 0; probe < 8; probe++) {
            int i = (start + probe) & (CACHE_SLOTS - 1);
            if (this.cacheUsed[i]) {
                if (this.cacheKeys[i] == key) return this.cacheValues[i];
            } else {
                int[] v = decompose(x0, y0, x1, y1);
                this.cacheUsed[i] = true;
                this.cacheKeys[i] = key;
                this.cacheValues[i] = v;
                return v;
            }
        }
        // 缓存饱和：覆盖首个探测槽（自愈），并直接返回本次结果
        int i = start & (CACHE_SLOTS - 1);
        int[] v = decompose(x0, y0, x1, y1);
        this.cacheKeys[i] = key;
        this.cacheValues[i] = v;
        return v;
    }

    /**
     * 贪心分解（只在缓存未命中时跑，每个包围盒一次）。
     *
     * <p>算法：维护一组"仍在长高"的矩形（列范围 + 起始行）。每读一行，先取该行的连续不透明段；
     * 能与某个活动矩形<b>列范围完全一致</b>的段 ⇒ 该矩形长高一行；其余段 ⇒ 关闭（写入结果）或新开。
     * 行尾仍活动的矩形在最后一行统一关闭。超限一律返回 {@code null}（调用方整面回退）。</p>
     */
    @Nullable
    private int[] decompose(int x0, int y0, int x1, int y1) {
        byte[] a = this.alpha;
        int w = this.width;
        int cap = MAX_RECTS_PER_FACE;
        int[] out = new int[4 * cap];
        int outN = 0;

        int[] openXa = new int[cap];
        int[] openXb = new int[cap];
        int[] openYa = new int[cap];
        int openN = 0;
        int[] runXa = new int[cap];
        int[] runXb = new int[cap];
        boolean[] runTaken = new boolean[cap];
        int[] nextXa = new int[cap];
        int[] nextXb = new int[cap];
        int[] nextYa = new int[cap];

        for (int y = y0; y <= y1; y++) {
            int row = y * w;
            int runN = 0;
            int x = x0;
            while (x <= x1) {
                if ((a[row + x] & 0xFF) >= ALPHA_CUTOFF) {
                    int xa = x;
                    while (x <= x1 && (a[row + x] & 0xFF) >= ALPHA_CUTOFF) x++;
                    if (runN >= cap) return null;
                    runXa[runN] = xa;
                    runXb[runN] = x - 1;
                    runTaken[runN] = false;
                    runN++;
                } else {
                    x++;
                }
            }

            int nextN = 0;
            for (int i = 0; i < openN; i++) {
                int match = -1;
                for (int j = 0; j < runN; j++) {
                    if (!runTaken[j] && runXa[j] == openXa[i] && runXb[j] == openXb[i]) {
                        match = j;
                        break;
                    }
                }
                if (match >= 0) {
                    runTaken[match] = true;                  // 同宽 ⇒ 继续长高
                    nextXa[nextN] = openXa[i];
                    nextXb[nextN] = openXb[i];
                    nextYa[nextN] = openYa[i];
                    nextN++;
                } else {
                    if (outN + 4 > out.length) return null;  // 段数超限 ⇒ 整面回退
                    out[outN++] = openXa[i];
                    out[outN++] = openXb[i];
                    out[outN++] = openYa[i];
                    out[outN++] = y - 1;                     // 结束于上一行
                }
            }
            for (int j = 0; j < runN; j++) {
                if (runTaken[j]) continue;
                if (nextN >= cap) return null;
                nextXa[nextN] = runXa[j];
                nextXb[nextN] = runXb[j];
                nextYa[nextN] = y;
                nextN++;
            }
            System.arraycopy(nextXa, 0, openXa, 0, nextN);
            System.arraycopy(nextXb, 0, openXb, 0, nextN);
            System.arraycopy(nextYa, 0, openYa, 0, nextN);
            openN = nextN;
        }

        for (int i = 0; i < openN; i++) {
            if (outN + 4 > out.length) return null;
            out[outN++] = openXa[i];
            out[outN++] = openXb[i];
            out[outN++] = openYa[i];
            out[outN++] = y1;
        }

        if (outN == out.length) return out;
        int[] trimmed = new int[outN];
        System.arraycopy(out, 0, trimmed, 0, outN);
        return trimmed;    // 只在建缓存时分配一次
    }

    private static long key(int x0, int y0, int x1, int y1) {
        return ((long) x0 << 48) | ((long) y0 << 32) | ((long) x1 << 16) | (long) y1;
    }

    private static int index(long key) {
        long h = key * -7046029254386353131L;      // 64 位混合常量（Knuth/Fibonacci 风格）
        return (int) (h >>> 56) & (CACHE_SLOTS - 1);
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
