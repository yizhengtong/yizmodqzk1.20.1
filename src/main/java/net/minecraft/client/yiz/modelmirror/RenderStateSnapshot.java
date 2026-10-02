package net.minecraft.client.yiz.modelmirror;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import org.lwjgl.opengl.GL11;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 世界渲染阶段「全局状态快照 / 全量还原」—— 体表特效 pass（{@link ModelMirrorRenderer}）、体积火焰
 * （{@code FlameRenderer}）、空气电弧（{@code LightningRenderer}）、描边后处理（{@code LockOutlineRenderer}）
 * 共用的收尾工具。
 *
 * <p><b>为什么必须有它</b>：这些 pass 都跑在 {@code RenderStage} 事件里，也就是<b>夹在别人的渲染中间</b>。
 * 它们（以及它们触发的 {@code RenderType.setup/clearRenderState}）一旦改了全局状态又没还回去，
 * 后面所有渲染都会带着错的状态继续跑 —— 表现为"进过一次特效就整屏发黑/花屏，而且不会自愈"。
 * 用快照 + {@code finally} 无条件还原，任何退出路径（正常返回、层未激活、提前 continue、异常）
 * 都回到"进来时那一份"，不依赖每个 pass 自己记得还哪几项。</p>
 *
 * <p><b>1.20.1 的坑（决定了这里必须"读 GL 真值"而不是读缓存）</b>：</p>
 * <ul>
 *   <li>{@code RenderSystem.setShaderTexture(i, id)} <b>只写内部数组</b>
 *       {@code shaderTextures[i]}（{@code RenderSystem.java:839-844}），真正的 GL 绑定发生在
 *       draw 时：{@code VertexBuffer._drawWithShader} 用 {@code RenderSystem.getShaderTexture(i)}
 *       填 {@code samplerMap}，再由 {@code ShaderInstance.apply()} 绑（{@code ShaderInstance.java:345-364}）。
 *       所以<b>"纹理单元泄漏"泄漏的是那个数组</b>，而且一旦脏了，之后每个"程序里有 Sampler<i>i</i>、
 *       RenderType 自己又没设贴图"的 draw 都会照脏值绑 —— 描边 FBO 的颜色纹理每帧被清成 (0,0,0,0)
 *       （{@code LockOutlineFramebuffer}），绑上它就是<b>整片纯黑</b>。</li>
 *   <li>{@code RenderSystem.getShaderColor()} <b>返回内部数组本身</b>（{@code RenderSystem.java:512-515}），
 *       所以"先存引用、后还原"的写法会被自己那次 {@code setShaderColor} 覆盖成垃圾值
 *       （{@code FlameRenderer} 踩过：还原等于强制刷白）。这里一律<b>拷贝成 float[4]</b>。</li>
 *   <li>混合/深度/剔除/深度掩码都没有 getter，只能 {@code GlStateManager._getInteger(枚举)} 读真值。
 *       还原走 {@code RenderSystem}/{@code GlStateManager} 的写入口（它们内部有缓存比较，
 *       值没变时不会真的发 GL 调用），缓存因此不会被绕过。</li>
 * </ul>
 *
 * <p><b>为什么要还的每一项</b>（逐项写清，避免"看不懂就别还"）：</p>
 * <ol>
 *   <li><b>当前 RenderTarget（FBO 绑定）</b>：体表 pass 会把输出切到主目标（深度纹理截断 + 输出必须同源），
 *       不还的话后续 pass 会画进错误的 FBO（严重时主目标整帧收不到内容 → 画面冻结/发黑）。
 *       <b>还原策略</b>：只有"本 pass 确实切过（{@link #ownFramebuffer()}）"或"当前绑定确实被别人留脏了"
 *       才动它，而且<b>永远不会还原到 0 号默认帧缓冲</b>（那是 {@code RenderTarget.clear()}/
 *       {@code unbindWrite()} 的残留；世界渲染阶段的目标恒为主渲染目标）。</li>
 *   <li><b>viewport</b>：{@code RenderTarget.clear()} 与 {@code _resize} 都会顺手
 *       {@code _viewport(0,0,this.viewWidth,this.viewHeight)}（{@code RenderTarget.java:200-207, 57-66}）。
 *       它是<b>全局</b>状态且没有 getter 缓存，描边 FBO 的 clear 会把整个世界渲染的裁剪区改成
 *       "那个 FBO 的尺寸"——窗口缩放/全屏切换的那一帧两边尺寸不同，就是整屏错位/发黑。</li>
 *   <li><b>12 个纹理单元的 shaderTextures 记录</b>：见上，火焰层借 0 号单元当"场景深度"，
 *       描边后处理借 0 号单元当"描边填充图"，不还就是全局黑图。</li>
 *   <li><b>当前 ShaderInstance</b>：{@code ShaderStateShard} 只 set 不 clear，pass 结束后残留的是特效着色器；
 *       之后任何"不自己 setShader 的立即绘制"（{@code BufferUploader.drawWithShader}）都会用它。</li>
 *   <li><b>着色器颜色（ColorModulator）</b>：闪电层用 {@code setShaderColor(fade,…)} 做渐显渐隐，
 *       它是全局染色，漏出去会让后续所有绘制一起变暗。</li>
 *   <li><b>混合开关 + 混合函数</b>：特效层用加法/预乘混合，漏出去后续叠加关系全乱。</li>
 *   <li><b>深度测试开关 + 深度掩码</b>：特效层是 {@code NO_DEPTH_TEST}/{@code COLOR_WRITE}，
 *       它们的 {@code clearRenderState} 会顺手 {@code disableDepthTest()} 与 {@code depthMask(true)}
 *       —— 这是"看着对称、其实不对称"的一处，必须由本快照兜住。</li>
 *   <li><b>背面剔除</b>：特效几何两面都要发（{@code NO_CULL}），还原成进来时的状态。</li>
 * </ol>
 *
 * <p>所有方法都必须在渲染线程调用（{@code GlStateManager._getInteger} 自带断言）。</p>
 */
public final class RenderStateSnapshot {

    private static final Logger LOGGER = LoggerFactory.getLogger("YizModelMirror");

    // ── GL 枚举（1.20.1 没给这些状态提供 getter，只能自己带枚举值读真值）──
    private static final int GL_BLEND = 3042;
    private static final int GL_BLEND_SRC_RGB = 32969;
    private static final int GL_BLEND_DST_RGB = 32968;
    private static final int GL_BLEND_SRC_ALPHA = 32970;
    private static final int GL_BLEND_DST_ALPHA = 32971;
    private static final int GL_COLOR_WRITEMASK = 3106;
    private static final int GL_CULL_FACE = 2884;
    private static final int GL_DEPTH_TEST = 2929;
    private static final int GL_DEPTH_WRITEMASK = 2930;
    private static final int GL_FRAMEBUFFER = 36160;
    private static final int GL_VIEWPORT = 2978;

    /** 纹理单元数量：{@code RenderSystem.shaderTextures} 固定 12 个（{@code RenderSystem.java:85}）。 */
    public static final int TEXTURE_UNITS = 12;

    private final int boundFramebuffer;
    /** 入口时的 viewport（x, y, w, h）。 */
    private final int[] viewport = new int[4];
    private final int[] shaderTextures = new int[TEXTURE_UNITS];
    private final float[] shaderColor = new float[4];
    private final ShaderInstance shader;
    private final boolean blendEnabled;
    private final int blendSrcRgb, blendDstRgb, blendSrcAlpha, blendDstAlpha;
    private final boolean cullEnabled;
    private final boolean depthTestEnabled;
    private final boolean depthMaskEnabled;
    /** 颜色掩码 R/G/B/A（云层 pass 会把它关掉再打开；体积火焰历史上是"无条件刷成 true"兜底）。 */
    private final boolean[] colorMask = new boolean[4];

    /**
     * 本 pass 是否<b>自己切过渲染目标</b>（{@link #ownFramebuffer()}）。
     * true = 退出时把渲染目标交还给主渲染目标；false = 只有"确实被别人留在了别的 FBO 上"时才纠正。
     */
    private boolean framebufferOwned;

    private boolean restored;

    private RenderStateSnapshot() {
        this.boundFramebuffer = GlStateManager.getBoundFramebuffer();
        GL11.glGetIntegerv(GL_VIEWPORT, this.viewport);
        for (int i = 0; i < TEXTURE_UNITS; i++) {
            this.shaderTextures[i] = RenderSystem.getShaderTexture(i);
        }
        // 必须逐值拷贝：getShaderColor() 返回的是 RenderSystem 的内部数组，存引用会在自己
        // setShaderColor(...) 之后变成"还原成自己刚写进去的值"（FlameRenderer 的历史 bug）。
        float[] color = RenderSystem.getShaderColor();
        System.arraycopy(color, 0, this.shaderColor, 0, 4);
        this.shader = RenderSystem.getShader();
        this.blendEnabled = GlStateManager._getInteger(GL_BLEND) != 0;
        this.blendSrcRgb = GlStateManager._getInteger(GL_BLEND_SRC_RGB);
        this.blendDstRgb = GlStateManager._getInteger(GL_BLEND_DST_RGB);
        this.blendSrcAlpha = GlStateManager._getInteger(GL_BLEND_SRC_ALPHA);
        this.blendDstAlpha = GlStateManager._getInteger(GL_BLEND_DST_ALPHA);
        this.cullEnabled = GlStateManager._getInteger(GL_CULL_FACE) != 0;
        this.depthTestEnabled = GlStateManager._getInteger(GL_DEPTH_TEST) != 0;
        this.depthMaskEnabled = GlStateManager._getInteger(GL_DEPTH_WRITEMASK) != 0;
        // 颜色掩码是 4 个值，GlStateManager 只暴露一个标量 getter（_getInteger 只回第一个分量），
        // 所以这里直接向 GL 要整个数组 —— 读操作不影响 GlStateManager 的写缓存（写仍走它的入口）。
        int[] mask = new int[4];
        GL11.glGetIntegerv(GL_COLOR_WRITEMASK, mask);
        for (int i = 0; i < 4; i++) this.colorMask[i] = mask[i] != 0;
    }

    /** 快照当前全局渲染状态（渲染线程）。 */
    public static RenderStateSnapshot capture() {
        return new RenderStateSnapshot();
    }

    /**
     * 声明「本 pass 自己切换过渲染目标」：退出时把渲染目标交还给<b>主渲染目标</b>，而不是"入口时抓到的那个值"。
     *
     * <p>为什么需要这个开关（描边 fill / 后处理那类 pass 必须调）：{@link #capture()} 读的是"入口那一刻
     * GL 上绑的是谁"。如果某个 pass 在**同一渲染阶段内**先切到自己的 FBO、再由别人把它切回来，
     * "还原成入口值"就可能还原到一个<b>已经不是本阶段该有的目标</b>；反过来，如果入口值本身就是
     * 0（默认帧缓冲 —— {@code RenderTarget.clear()}/{@code unbindWrite()} 的常见残留，见
     * {@code RenderTarget.java:284-296}），"忠实还原成 0"会让本阶段之后的绘制全部画进窗口后缓冲，
     * 而画面是从主渲染目标 blit 出去的 → 该画的东西全部丢失（表现就是"整屏黑/画面冻结"）。
     * 世界渲染阶段的正确目标恒为主渲染目标，所以这里以它为准。</p>
     */
    public void ownFramebuffer() {
        this.framebufferOwned = true;
    }

    /**
     * 全量还原到快照那一刻。必须在 {@code finally} 里调用（正常返回 / 提前 continue / 抛异常都要还）。
     *
     * @param reason 触发还原的 pass 短名（只用于"确实改动了状态"时的限流诊断）
     */
    public void restore(String reason) {
        if (restored) return;   // 幂等：防止调用方重复还原造成状态二次漂移
        restored = true;

        int textureUnitsDirty = 0;
        for (int i = 0; i < TEXTURE_UNITS; i++) {
            if (RenderSystem.getShaderTexture(i) != shaderTextures[i]) {
                RenderSystem.setShaderTexture(i, shaderTextures[i]);
                textureUnitsDirty++;
            }
        }
        boolean shaderDirty = RenderSystem.getShader() != shader;
        if (shaderDirty) RenderSystem.setShader(() -> shader);

        float[] color = RenderSystem.getShaderColor();
        boolean colorDirty = color[0] != shaderColor[0] || color[1] != shaderColor[1]
                || color[2] != shaderColor[2] || color[3] != shaderColor[3];
        if (colorDirty) {
            RenderSystem.setShaderColor(shaderColor[0], shaderColor[1], shaderColor[2], shaderColor[3]);
        }

        // ── 渲染目标：只在「确实被改过」时才动它（见 ownFramebuffer 的注释）──
        // want < 0 = 不需要动；0 号（默认帧缓冲）永远不还原 —— 世界渲染阶段的目标恒为主渲染目标。
        int currentFbo = GlStateManager.getBoundFramebuffer();
        int mainFbo = Minecraft.getInstance().getMainRenderTarget().frameBufferId;
        int want = -1;
        if (framebufferOwned) {
            want = mainFbo;                                   // 我们切走过 → 明确交还主目标
        } else if (currentFbo != boundFramebuffer) {
            want = boundFramebuffer != 0 ? boundFramebuffer : mainFbo;   // 别人留的脏值 → 纠正（但绝不回到 0）
        }
        boolean fboDirty = want >= 0 && currentFbo != want;
        if (fboDirty) GlStateManager._glBindFramebuffer(GL_FRAMEBUFFER, want);

        // ── viewport：{@code RenderTarget.clear()}/_resize 会顺手把它改成那个 FBO 的尺寸
        //    （{@code RenderTarget.java:200-207}），而它是全局的 —— 不还回去，后面整个世界都在
        //    "描边 FBO 的 viewport"下渲染（缩放/全屏那一帧两边尺寸不同就是整屏错位/发黑）。
        int[] vp = new int[4];
        GL11.glGetIntegerv(GL_VIEWPORT, vp);
        boolean viewportDirty = vp[0] != viewport[0] || vp[1] != viewport[1]
                || vp[2] != viewport[2] || vp[3] != viewport[3];
        if (viewportDirty) GlStateManager._viewport(viewport[0], viewport[1], viewport[2], viewport[3]);

        // 下面几项走写入口：GlStateManager 内部有缓存比较，值没变时不会真的发 GL 调用，
        // 所以"无条件调用"既便宜又不会与缓存漂移。
        if (blendEnabled) RenderSystem.enableBlend(); else RenderSystem.disableBlend();
        RenderSystem.blendFuncSeparate(blendSrcRgb, blendDstRgb, blendSrcAlpha, blendDstAlpha);
        if (cullEnabled) RenderSystem.enableCull(); else RenderSystem.disableCull();
        if (depthTestEnabled) RenderSystem.enableDepthTest(); else RenderSystem.disableDepthTest();
        RenderSystem.depthMask(depthMaskEnabled);

        // 颜色掩码：四个分量分开读（见构造器），只在真的被关掉过时才还。
        int[] mask = new int[4];
        GL11.glGetIntegerv(GL_COLOR_WRITEMASK, mask);
        boolean maskDirty = (mask[0] != 0) != colorMask[0] || (mask[1] != 0) != colorMask[1]
                || (mask[2] != 0) != colorMask[2] || (mask[3] != 0) != colorMask[3];
        if (maskDirty) RenderSystem.colorMask(colorMask[0], colorMask[1], colorMask[2], colorMask[3]);

        // ── 临时诊断：只有"确实还了什么"才记一行（每 pass 每种脏法只记一次），
        //    用来定位"到底是哪个 pass 把全局状态改脏了"。──
        if (textureUnitsDirty > 0 || shaderDirty || colorDirty || fboDirty || viewportDirty || maskDirty) {
            if (MirrorDiag.first("mm:restore:" + reason + ":" + textureUnitsDirty + ":" + shaderDirty
                    + ":" + colorDirty + ":" + fboDirty + ":" + viewportDirty + ":" + maskDirty)) {
                LOGGER.info("[RenderState] pass={} 退出前还回全局状态: 纹理单元改动={} 着色器改动={} 着色器颜色改动={} FBO改动={} viewport改动={} 颜色掩码改动={}"
                                + "（这些就是『改了没还』会污染后续渲染、且不会自愈的那几项；"
                                + "FBO/viewport 被改脏 = 后面所有绘制都画进错误的缓冲或错误的裁剪区 → 整屏黑/画面冻结）",
                        reason, textureUnitsDirty, shaderDirty, colorDirty, fboDirty, viewportDirty, maskDirty);
            }
        }
    }

    /** 快照里的 0 号纹理单元值（体表火焰要拿它做"场景深度"绑定前的原值核对，诊断用）。 */
    public int textureUnit(int unit) {
        return unit >= 0 && unit < TEXTURE_UNITS ? shaderTextures[unit] : 0;
    }

    /** 快照里的 draw framebuffer id（描边管线核对"入口时到底是哪个目标"用）。 */
    public int framebuffer() {
        return boundFramebuffer;
    }
}
