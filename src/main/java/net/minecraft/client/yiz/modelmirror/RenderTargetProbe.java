package net.minecraft.client.yiz.modelmirror;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.yiz.client.render.LockOutlineRenderer;
import net.minecraft.client.yiz.tool.YizDiagnostics;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import org.lwjgl.opengl.GL11;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * <b>【临时诊断】</b>黑屏现场取证：一次把"当前绑的是哪个渲染目标 / 是不是主目标 / viewport /
 * 描边 FBO / 0 号纹理单元 / 当前着色器 / 玩家身上的状态效果"读出来。
 *
 * <p><b>为什么需要它（这一轮要钉死的东西）</b>：黑屏类的现场都满足"读一次 GL 真值就能判死" ——
 * 世界画面是从主渲染目标 blit 出去的，只要某一帧里有绘制落在别的 FBO（典型：描边 FBO、0 号默认帧缓冲），
 * 画面就整片发黑且不会自愈；反过来，如果每一项都是"主目标 + 主目标尺寸的 viewport"，
 * 那就<b>排除了渲染目标串台</b>这条线，得去别处找（例如原版"黑暗"效果）。</p>
 *
 * <p>两条证据通道：</p>
 * <ol>
 *   <li>{@link #once(String)}：挂在镜像 pass 与描边 pass 上，<b>只在确实异常时</b>每个阶段打一条
 *       （异常 = 绑定的不是主目标；或玩家身上带着"黑暗"效果）。正常每帧一条都不打；</li>
 *   <li>{@link #describe(String)} / {@link #logNow(String)}：给 {@code /yiz fxtoggle probe} 用 ——
 *       用户在黑屏<b>当场</b>敲一下，聊天栏 + 日志里就有一行完整现场（比事后翻日志准得多）。</li>
 * </ol>
 *
 * <p>日志项登记在 {@link YizDiagnostics}（{@code fx_blackscreen}），所以可以用
 * {@code /yiz diag fx_blackscreen off} 单独静默它，不必改代码。<b>定位完可以整类删掉</b>
 * （调用点 3 处：{@code ModelMirrorRenderer}、{@code LockOutlineRenderer}、{@code YizFxToggleCommand}）。</p>
 *
 * <p>所有方法都必须在<b>渲染线程</b>调用（{@code GlStateManager} / {@code GL11} 都带线程断言）——
 * {@code /yiz fxtoggle probe} 因此先用 {@code Minecraft#execute} 切到客户端线程再读。</p>
 */
public final class RenderTargetProbe {

    private static final Logger LOGGER = LoggerFactory.getLogger("YizFxBlack");

    // ── GL 枚举（1.20.1 没给这些状态提供命名的公共常量入口，按 RenderStateSnapshot 的写法自带枚举值）──
    /** GL_READ_FRAMEBUFFER：回读描边 FBO 时绑"读"目标，不动"画"目标（否则会打断正在进行的绘制）。 */
    private static final int GL_READ_FRAMEBUFFER = 36008;
    /** GL_READ_FRAMEBUFFER_BINDING：回读前的读目标，读完还回去。 */
    private static final int GL_READ_FRAMEBUFFER_BINDING = 36010;
    /** GL_COLOR_ATTACHMENT0：FBO 的颜色附件（glReadBuffer 用）。 */
    private static final int GL_COLOR_ATTACHMENT0 = 36064;
    /** GL_BACK：默认帧缓冲的读缓冲（还回 0 号目标时用）。 */
    private static final int GL_BACK = 1029;

    /** 已放行过的"渲染目标异常"key（每个阶段只打一条，避免每帧刷屏）。 */
    private static final Set<String> SEEN = new HashSet<>();
    /** "玩家带黑暗效果"这条证据只打一条（它是结论性证据，出现一次就够）。 */
    private static boolean darknessLogged;
    /** 掩码回读取多少条横带（每条 = {@code 2r+1} 行，带内每 r 个像素一个采样点）。 */
    private static final int MASK_BANDS = 16;

    private RenderTargetProbe() {}

    /**
     * 现场快照（一行文本，<b>只在渲染线程调用</b>）。
     *
     * @param where 阶段短名（日志可读性用，如 {@code modelmirror} / {@code lock-after-entities} / {@code probe}）
     */
    public static String describe(String where) {
        Minecraft mc = Minecraft.getInstance();
        int bound = GlStateManager.getBoundFramebuffer();
        int main = mc.getMainRenderTarget().frameBufferId;
        int[] vp = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, vp);
        ShaderInstance shader = RenderSystem.getShader();
        return "阶段=" + where
                + " 绑定FBO=" + bound + (bound == main ? "(=主目标)" : "(不是主目标!)")
                + " 主目标FBO=" + main
                + " viewport=" + vp[2] + "x" + vp[3] + "@" + vp[0] + "," + vp[1]
                + " 主目标尺寸=" + mc.getMainRenderTarget().width + "x" + mc.getMainRenderTarget().height
                + " 描边FBO[" + LockOutlineRenderer.outlineTargetInfo() + "]"
                + " 纹理单元0=" + RenderSystem.getShaderTexture(0)
                + " 当前着色器=" + (shader == null ? "null" : shader.getName())
                + " 玩家效果=[" + effectSummary() + "]";
    }

    /**
     * 异常时打一条（限流：同一阶段只打一次）。挂在镜像 pass 与描边 pass 入口。
     *
     * <p>只报两类<b>确实可判</b>的异常：</p>
     * <ol>
     *   <li>绑定的不是主渲染目标（画面被画到别人身上，整片发黑/冻结的直接来源）；</li>
     *   <li>玩家身上带着原版<b>黑暗</b>效果（vanilla 监守者每 120 tick 给 20 格内玩家挂 260 tick 黑暗，
     *       {@code Warden.java:254-262} + {@code :371-374}）—— 若黑屏总与它同时出现，
     *       黑屏就是原版黑暗效果，与我们的 pass 无关。</li>
     * </ol>
     */
    public static void once(String where) {
        if (!YizDiagnostics.isOn(YizDiagnostics.FX_BLACKSCREEN)) return;
        Minecraft mc = Minecraft.getInstance();

        if (GlStateManager.getBoundFramebuffer() != mc.getMainRenderTarget().frameBufferId
                && SEEN.add("fbo:" + where)) {
            LOGGER.warn("[FxBlack] 绘制时绑定的渲染目标不是主目标 —— {}"
                            + "（主目标之外的目标收不到 blit，画面整片发黑/冻结且不会自愈；"
                            + "本行一出即可判定渲染目标串台，请把整行发回）",
                    describe(where));
            return;   // 这一行已经带上了完整现场（含玩家效果），不必再打第二条
        }

        if (!darknessLogged && playerHasDarkness()) {
            darknessLogged = true;
            LOGGER.warn("[FxBlack] 玩家当前带原版【黑暗 Darkness】效果 → {}"
                            + "（监守者每 120t 给 20 格内玩家挂 260t 黑暗；黑暗效果本身就会把画面压成黑色。"
                            + "若黑屏与它同步出现，黑屏与渲染 pass 无关）",
                    describe(where));
        }
    }

    /** 无条件打一条完整现场（给 {@code /yiz fxtoggle probe}；调用次数由用户决定，故不额外限流）。 */
    public static void logNow(String where) {
        LOGGER.warn("[FxBlack] 现场取证（用户手动触发）—— {}", describe(where));
    }

    // ────────────────────────── 描边 FBO 覆盖度 / 掩码实心度（可重复触发） ──────────────────────────

    private static boolean coverageSampled;
    /** 「再来一次」请求位：{@code /yiz fxtoggle probe} 每次都置位（回读会同步管线，不能每帧做）。 */
    private static volatile boolean coverageRequested;

    /** 请求下一帧的 lock-post 阶段重新做一次填充回读（指令线程调用；渲染线程消费）。 */
    public static void requestCoverageSample() {
        // ⚠️ 第三轮（黑屏回退）：这里曾经每次都翻转「按贴图 alpha 镂空」开关（uCutout），
        //    用来做"镂空开/关"两次回读的 A/B 面积对照。那个开关随镂空一起回退了
        //    （理由见 LockOutlineRenderType 类注释：同一次提交里的全局状态改动让整屏发黑），
        //    所以现在只回读、**不改任何渲染状态**。要重做 A/B 请等镂空安全重做之后再加。
        LOGGER.warn("[FxBlack] 下一次掩码回读：只回读描边 FBO 并打印掩码统计，"
                        + "不翻转任何开关（镂空 A/B 已随第三轮黑屏回退删除；要重做镂空请走 "
                        + "LockOutlineRenderType 类注释里的三条安全路线）");
        coverageRequested = true;
    }

    /**
     * 回读描边 FBO，报两组数字：<b>①采样线覆盖度（旧指标，便于和上一轮日志对比）</b>、
     * <b>②掩码实心度 + 后处理真正会涂的屏幕占比（新指标，直接决定"会不会整屏糊色"）</b>。
     *
     * <p><b>为什么旧的"采样线覆盖度"不足以判死</b>：它只回答"这条线上有多少像素是填充"，
     * 而后处理涂不涂色只取决于<b>中心是否被填充 + 7×7 邻域覆盖率 density</b>
     * （本轮的滞后双阈值判据：{@code filled = density >= 0.55}、{@code solid = density >= 0.90}、
     * {@code band = filled*(1-solid)*centerEmpty}，见 {@code rendertype_lock_post.fsh}）：</p>
     * <ul>
     *   <li>填充是<b>实心</b>的（实体内部 density=1 ⇒ solid=true ⇒ band=0 不画）⇒ 只有外轮廓那一圈会被涂，
     *       哪怕整屏 90% 都是填充也<b>不会糊色</b>。此时"覆盖度"只等于"目标实体的投影面积"
     *       （近距离大目标本来就是几十个百分点，与"几何被填多"无关）；</li>
     *   <li>填充是<b>细碎网点</b>的（邻域覆盖率恒≈该区填充率≈0.5 &lt; 0.55）⇒ {@code filled=false} ⇒ band 恒为 0
     *       ⇒ <b>结构性不糊色</b>（这正是本轮判据的意义：网点与真边界靠"邻域能否接近全满"分开，
     *       而不是靠"density 是否≈0.5"——后者对网点恒成立，是旧版整屏糊色的根因）。</li>
     * </ul>
     *
     * <p>所以这里在 CPU 上<b>照抄后处理的邻域判据</b>（半径取 {@link LockOutlineRenderer#postRadiusPx()}，
     * 与 shader 的 {@code uRadius} 同源），在铺满屏幕的采样网格上统计：</p>
     * <ol>
     *   <li>{@code 填充点占屏幕}：alpha&gt;0.1 的像素占比 = 目标实体的投影面积（旧的覆盖度指标）；</li>
     *   <li>{@code 掩码实心度}：填充点里"邻域覆盖率 ≥ 0.9"的占比 ⇒ 越接近 100% 越说明掩码是实心的
     *       （即"填充只含实体本体、没有网点"）；</li>
     *   <li>{@code 后处理涂色占比}：<b>真正会糊在屏幕上的像素占比</b> = 中心<b>未</b>被填充
     *       且邻域覆盖率落在 [0.55, 0.90) 的采样点占比（= shader 里 band 非零的条件）。
     *       这一项才是"黑屏/糊色"的直接指标，<b>修好后应当是个位数百分比</b>（只是一圈轮廓带）。</li>
     * </ol>
     *
     * <p>默认只在第一次跑到这里时回读一次；{@link #requestCoverageSample()} 可以再放行一次
     * （{@code /yiz fxtoggle probe} 就是这么用的）。</p>
     */
    public static void sampleOutlineCoverage(String where) {
        if (!YizDiagnostics.isOn(YizDiagnostics.FX_BLACKSCREEN)) return;
        if (coverageSampled && !coverageRequested) return;
        int fbo = LockOutlineRenderer.outlineFramebufferId();
        int w = LockOutlineRenderer.outlineFramebufferWidth();
        int h = LockOutlineRenderer.outlineFramebufferHeight();
        // FBO 还没建（AFTER_SKY 之前）→ 保留请求位，等它就绪的那一帧再采
        if (fbo < 0 || w <= 0 || h <= 0) return;
        coverageRequested = false;
        coverageSampled = true;

        String summary;
        String mask;
        int prevRead = GlStateManager._getInteger(GL_READ_FRAMEBUFFER_BINDING);
        java.nio.ByteBuffer row = null;
        try {
            GlStateManager._glBindFramebuffer(GL_READ_FRAMEBUFFER, fbo);
            GL11.glReadBuffer(GL_COLOR_ATTACHMENT0);
            row = org.lwjgl.system.MemoryUtil.memAlloc(Math.max(w, h) * 4);

            StringBuilder sb = new StringBuilder();
            float min = 1.0f, max = 0.0f, sum = 0.0f;
            int lines = 0;
            for (int i = 1; i <= 3; i++) {
                // 横线（y = 1/4, 1/2, 3/4 处整行，一次 glReadPixels 读完整行）
                int y = Math.max(0, Math.min(h - 1, h * i / 4));
                float cov = lineCoverage(row, 0, y, w, true);
                min = Math.min(min, cov); max = Math.max(max, cov); sum += cov; lines++;
                sb.append(" 横").append(i).append("/4=").append(pct(cov));
                // 竖线（x = 1/4, 1/2, 3/4 处整列，同样一次读完）
                int x = Math.max(0, Math.min(w - 1, w * i / 4));
                float covCol = lineCoverage(row, x, 0, h, false);
                min = Math.min(min, covCol); max = Math.max(max, covCol); sum += covCol; lines++;
                sb.append(" 竖").append(i).append("/4=").append(pct(covCol));
            }
            summary = "最小=" + pct(min) + " 平均=" + pct(sum / Math.max(1, lines)) + " 最大=" + pct(max) + " ——" + sb;

            org.lwjgl.system.MemoryUtil.memFree(row);
            row = null;
            mask = maskStats(w, h);
        } catch (Throwable t) {
            summary = "回读失败（不影响渲染）: " + t;
            mask = "掩码统计失败: " + t;
        } finally {
            if (row != null) org.lwjgl.system.MemoryUtil.memFree(row);
            GL11.glReadBuffer(prevRead == 0 ? GL_BACK : GL_COLOR_ATTACHMENT0);
            GlStateManager._glBindFramebuffer(GL_READ_FRAMEBUFFER, prevRead);
        }

        LOGGER.warn("[FxBlack] 描边 FBO 回读（{}，{}）: 采样线覆盖度 {} ；掩码 {}"
                        + "（采样线覆盖度 = 该线上 alpha>0.1 的占比，只等于「目标实体投影面积」，"
                        + "近距离大目标本来就是几十个百分点；判「会不会整屏糊色」要看掩码那一组："
                        + "实心度高 ⇒ 只有外轮廓一圈被涂；后处理涂色占比才是会糊在屏幕上的比例，修好后应是个位数）",
                where, describe(where), summary, mask);
    }

    /**
     * <b>掩码实心度 + 后处理真正会涂的屏幕占比</b>（CPU 侧照抄后处理的邻域判据）。
     *
     * <p>做法：在竖直方向取 {@value #MASK_BANDS} 条 7 行（{@code 2r+1} 行，r =
     * {@link LockOutlineRenderer#postRadiusPx()}）的横带，带内每隔 r 个像素取一个采样点，
     * 对每个点算邻域覆盖率 density（= 邻域内 alpha&gt;0.1 的像素占比），然后统计：</p>
     * <ul>
     *   <li>{@code 填充点占屏幕} = 采样点里"中心 alpha&gt;0.1"的占比（= 实体投影面积）；</li>
     *   <li>{@code 实心度} = 填充点里 density ≥ 0.9 的占比（越接近 100% 越说明掩码是实心的）；</li>
     *   <li>{@code 后处理涂色占比} = 采样点里"中心<b>未</b>填充 且 density ∈ [0.55, 0.90)"的占比
     *       —— 这正是 {@code band = filled*(1-solid)*centerEmpty} 非零的区域，
     *       也就是后处理真的会把 {@code c.rgb} 涂到屏幕上的比例。</li>
     * </ul>
     */
    private static String maskStats(int w, int h) {
        int r = Math.max(1, LockOutlineRenderer.postRadiusPx());
        int rows = 2 * r + 1;
        if (w <= 2 * r + 2 || h <= rows + 2) return "尺寸太小，跳过";
        java.nio.ByteBuffer buf = org.lwjgl.system.MemoryUtil.memAlloc(w * rows * 4);
        int pts = 0, fillPts = 0, solidPts = 0, paintedPts = 0;
        double fillDensitySum = 0.0;
        // ── 段数指标：把每条横带上的"填充点"按连续段切分 ──
        // 实心掩码（几何外壳）每带只有 1~3 段、且最长段几乎占满带内填充点；
        // 镂空后的掩码（细丝/网点）段数会成倍上涨、最长段占比明显下降。
        // 它是"掩码到底是外壳还是镂空形状"的结构性旁证（配合 A/B 面积对照一起看）。
        int totalRuns = 0;
        int longestRun = 0;
        int filledInBands = 0;
        try {
            int bands = MASK_BANDS;
            for (int b = 0; b < bands; b++) {
                // 每条横带的 y 起点：铺满竖直方向且不越界（底行 y0，向上读 rows 行）
                int y0 = (int) ((long) b * Math.max(1, h - rows) / Math.max(1, bands - 1));
                y0 = Math.max(0, Math.min(h - rows, y0));
                buf.clear();
                GL11.glReadPixels(0, y0, w, rows, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf);
                int cy = r;   // 邻域中心 = 带内第 r 行
                int runs = 0;
                int run = 0;
                for (int cx = r; cx + r < w; cx += Math.max(1, r)) {
                    pts++;
                    int centerA = buf.get((cy * w + cx) * 4 + 3) & 0xFF;
                    float density = neighbourhoodDensity(buf, w, cx, cy, r);
                    if (centerA > 25) {
                        fillPts++;
                        filledInBands++;
                        run++;
                        if (run > longestRun) longestRun = run;
                        fillDensitySum += density;
                        if (density >= 0.9f) solidPts++;
                    } else {
                        if (run > 0) runs++;
                        run = 0;
                        if (density >= 0.55f && density < 0.9f) {
                            // ★ 后处理真正会涂色的像素：中心未被填充 + 邻域"有掩码但不够实心"
                            //   = rendertype_lock_post.fsh 的 band = filled*(1-solid)*centerEmpty
                            //   （别忘了本判据只在【中心空】时成立 —— 这正是"描边长在掩码外沿"）。
                            paintedPts++;
                        }
                    }
                }
                if (run > 0) runs++;
                totalRuns += runs;
            }
        } finally {
            org.lwjgl.system.MemoryUtil.memFree(buf);
        }
        if (pts == 0) return "无采样点";
        float fillRatio = fillPts / (float) pts;
        float solidity = fillPts == 0 ? 0f : solidPts / (float) fillPts;
        float meanDensity = fillPts == 0 ? 0f : (float) (fillDensitySum / fillPts);
        float runsPerBand = totalRuns / (float) MASK_BANDS;
        float longestFrac = filledInBands == 0 ? 0f : longestRun / (float) filledInBands;
        return "半径=" + r + "px 锐度=" + LockOutlineRenderer.edgeSharpness()
                + " 填充点占屏幕=" + pct(fillRatio)
                + " 实心度(填充点里邻域≥90%的占比)=" + pct(solidity)
                + " 填充点平均邻域覆盖率=" + pct(meanDensity)
                + " 每带填充段数=" + String.format(java.util.Locale.ROOT, "%.1f", runsPerBand)
                + " 最长段占填充点=" + pct(longestFrac)
                + " ★后处理涂色占比=" + pct(paintedPts / (float) pts);
    }

    /** 邻域（{@code (2r+1)²} 个像素）里 alpha&gt;0.1 的占比；{@code buf} 是该横带（行号从 y0 递增）。 */
    private static float neighbourhoodDensity(java.nio.ByteBuffer buf, int w, int cx, int cy, int r) {
        int hit = 0;
        for (int j = cy - r; j <= cy + r; j++) {
            int rowBase = j * w;
            for (int i = cx - r; i <= cx + r; i++) {
                if ((buf.get((rowBase + i) * 4 + 3) & 0xFF) > 25) hit++;
            }
        }
        int total = (2 * r + 1) * (2 * r + 1);
        return hit / (float) total;
    }

    /**
     * 一条采样线上 alpha&gt;0.1 的像素占比。
     *
     * @param horizontal true = 从 (x0,y0) 向右读 w 个像素；false = 从 (x0,y0) 向上读 h 个像素
     */
    private static float lineCoverage(java.nio.ByteBuffer buf, int x0, int y0, int len, boolean horizontal) {
        if (len <= 0) return 0f;
        buf.clear();
        GL11.glReadPixels(x0, y0, horizontal ? len : 1, horizontal ? 1 : len,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf);
        int hit = 0;
        for (int i = 0; i < len; i++) {
            if ((buf.get(i * 4 + 3) & 0xFF) > 25) hit++;
        }
        return hit / (float) len;
    }

    private static String pct(float v) {
        return String.format(java.util.Locale.ROOT, "%.1f%%", v * 100f);
    }

    /** 玩家身上是否带原版"黑暗"效果（黑屏的第一嫌疑人，见 {@link #once}）。 */
    public static boolean playerHasDarkness() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && mc.player.hasEffect(MobEffects.DARKNESS);
    }

    /** 玩家当前全部状态效果的可读摘要（带剩余 tick），无效果返回"无"。 */
    public static String effectSummary() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return "无玩家";
        Collection<MobEffectInstance> active = mc.player.getActiveEffects();
        if (active.isEmpty()) return "无";
        StringBuilder sb = new StringBuilder();
        for (MobEffectInstance e : active) {
            if (sb.length() > 0) sb.append(" + ");
            sb.append(e.getEffect().getDisplayName().getString())
                    .append('(').append(e.getDuration()).append("t)");
        }
        return sb.toString();
    }
}
