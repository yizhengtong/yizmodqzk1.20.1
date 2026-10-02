package net.minecraft.client.yiz.client.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 锁定描边专用 FBO：把锁定实体穿墙渲染成纯色填充，供后处理 shader 采样做边缘检测。
 * 不依赖「极佳 Fabulous」画质（自己管理 FBO，普通画质可用）。
 *
 * <p><b>本类存在的另一半理由：把 {@code RenderTarget.clear()} 的副作用夹在一个方法里</b>。
 * 1.20.1 的 {@code RenderTarget.clear(boolean)} <b>不是</b>「清自己」这么单纯，它的实现是
 * （{@code RenderTarget.java:284-296}）：</p>
 *
 * <pre>
 * public void clear(boolean onMac) {
 *    this.bindWrite(true);        // ① 绑自己，并把 <b>全局 viewport</b> 改成自己的 viewWidth/viewHeight
 *    GlStateManager._clearColor(...); GlStateManager._clear(GL_COLOR|GL_DEPTH, onMac);
 *    this.unbindWrite();          // ② 解绑 → <b>GL 的 draw framebuffer 停在 0（默认帧缓冲）</b>
 * }
 * </pre>
 *
 * <p>也就是说：谁调用 {@code target.clear(...)}，谁就在调用点把「当前渲染目标」和「viewport」
 * 两件全局状态留在了<b>别人身上</b>（0 号 FBO = 窗口后缓冲，世界画面根本不在那里；viewport 是那个
 * FBO 的尺寸）。{@code RenderTarget._resize} 同样是这个收尾（{@code RenderTarget.java:57-66}）。</p>
 *
 * <p>所以本类把「清描边 FBO」与「交还主渲染目标」做成<b>一对必须成对使用的操作</b>：
 * {@link #clear()} 之后调用方<b>必须</b>紧接着 {@link #bindMain(Minecraft)}（或直接用
 * {@link #clearAndRestoreMain(Minecraft)}）。{@link #bindMain} 用 {@code bindWrite(true)}，
 * 因为本 FBO 的 {@code clear()} 已经把 viewport 改成了 FBO 的尺寸 —— 只有 {@code bindWrite(true)}
 * 会按主目标尺寸把它改回来（{@code bindWrite(false)} 会把这个错误的 viewport 一路带下去）。</p>
 *
 * <p><b>尺寸跟主渲染目标、不跟窗口</b>：{@link #resizeToMainTarget(Minecraft)} 取
 * {@code mainRenderTarget.width/height}（= 真正在渲染的分辨率），而窗口尺寸
 * （{@code Window.getWidth/Height}）在「窗口缩放 / F11 全屏切换」的那一帧会与主目标不一致
 * （主目标由 {@code Minecraft.resizeDisplay()} 在另一处重设）。两者不一致时，post pass 采样的
 * 贴图分辨率、{@code uTexelSize} 与屏幕像素都会错位，而且 viewport 会被设成窗口尺寸 —— 这是
 * 「缩放/全屏后整屏发黑」那一类问题的来源，所以这里只认主目标。</p>
 */
public final class LockOutlineFramebuffer {

    private static final Logger LOGGER = LoggerFactory.getLogger("YizModelMirror");

    private final RenderTarget target;
    /** 诊断限流：只在"确实检测到不一致"时打一条（见 {@link #diagOnce}）。 */
    private String lastDiag;

    public LockOutlineFramebuffer(int width, int height) {
        // RenderTarget 是抽象类：用匿名子类，沿用基类 createBuffers 创建标准颜色+深度 FBO
        this.target = new RenderTarget(true) { };
        this.target.setClearColor(0.0F, 0.0F, 0.0F, 0.0F);
        this.target.resize(Math.max(1, width), Math.max(1, height), Minecraft.ON_OSX);
    }

    /** 尺寸跟随主渲染目标（不是窗口，理由见类注释）。 */
    public void resizeToMainTarget(Minecraft mc) {
        RenderTarget main = mc.getMainRenderTarget();
        int w = Math.max(1, main.width);
        int h = Math.max(1, main.height);
        if (this.target.width != w || this.target.height != h) {
            this.target.resize(w, h, Minecraft.ON_OSX);
            // resize 内部同样以 _glBindFramebuffer(GL_FRAMEBUFFER, 0) 收尾（RenderTarget.java:57-66），
            // 所以这里也必须让调用方交还主目标 —— 本方法只负责尺寸。
            diagOnce("resize:" + w + "x" + h,
                    "[LockOutline] 描边 FBO 尺寸跟随主目标重设为 {}x{}（主目标 {}x{}）", w, h, main.width, main.height);
        }
    }

    /** 绑定描边 FBO 并把 viewport 设成本 FBO 尺寸（fill 的 3D 绘制必须与 FBO 同尺寸）。 */
    public void bindForFill() {
        this.target.bindWrite(true);
    }

    /**
     * 绑定本 FBO 并清空颜色/深度。
     *
     * <p><b>结束状态不是"绑着自己"</b>：{@code RenderTarget.clear} 内部会 {@code unbindWrite()}，
     * 于是 GL 的 draw framebuffer 停在 <b>0</b>、viewport 是 FBO 尺寸。必须紧接着
     * {@link #bindMain(Minecraft)}（或用 {@link #clearAndRestoreMain(Minecraft)}）。
     * 旧写法 {@code target.clear(); target.bindWrite(false);} 把 FBO 绑了回去、却把 viewport 留在
     * FBO 尺寸上，于是后面每一帧都在"FBO 尺寸的 viewport"下渲染世界。</p>
     */
    public void clear() {
        this.target.clear(Minecraft.ON_OSX);
    }

    /** {@link #clear()} + 交还主渲染目标（含 viewport）。AFTER_SKY 段用这个。 */
    public void clearAndRestoreMain(Minecraft mc) {
        clear();
        bindMain(mc);
    }

    /**
     * 把渲染目标交还给主渲染缓冲，<b>并同步 viewport</b>。
     *
     * <p>用 {@code bindWrite(true)} 而不是 {@code false}：本类的 {@link #clear()}（以及
     * {@code RenderTarget._resize}）已经把全局 viewport 改成了描边 FBO 的尺寸，只有
     * {@code true} 会按主目标的 viewWidth/viewHeight 把它改回来。主目标尺寸 = 正常渲染分辨率，
     * 这里没有"故意用别的 viewport"的场景（特效 pass 全屏绘制靠投影矩阵，不靠 viewport）。</p>
     */
    public static void bindMain(Minecraft mc) {
        mc.getMainRenderTarget().bindWrite(true);
    }

    /** 主目标的 draw framebuffer id（诊断/还原兜底用：任何"还原到 0"的路径都应该改成还原到这里）。 */
    public static int mainFramebufferId(Minecraft mc) {
        return mc.getMainRenderTarget().frameBufferId;
    }

    /** 投影矩阵的裁剪排序（post pass 用单位投影时也需要一个确定值）。 */
    public RenderTarget get() { return this.target; }

    public int getWidth() { return this.target.width; }

    public int getHeight() { return this.target.height; }

    /** FBO 颜色纹理 id，供后处理 shader 的 Sampler0 采样。 */
    public int getColorTextureId() { return this.target.getColorTextureId(); }

    /**
     * 诊断限流（同一 key 只打一次）：只在"两种尺寸/两个目标确实不一致"这类**异常**路径上调用，
     * 正常每帧路径一条都不打。
     */
    void diagOnce(String key, String format, Object... args) {
        if (key.equals(this.lastDiag)) return;
        this.lastDiag = key;
        LOGGER.warn(format + "（这是「渲染目标/viewport 被别人留在身上」那一类黑屏的判定依据："
                        + "描边 FBO 必须与主目标同尺寸，且任何 pass 结束后渲染目标必须是主目标）",
                args);
    }
}
