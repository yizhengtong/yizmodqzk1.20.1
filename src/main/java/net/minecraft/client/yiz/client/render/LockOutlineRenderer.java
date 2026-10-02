package net.minecraft.client.yiz.client.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.yiz.api.OutlineRegistry;
import net.minecraft.client.yiz.api.TargetFrameManager;
import net.minecraft.client.yiz.api.TargetFrameProvider;
import net.minecraft.client.yiz.modelmirror.RenderStateSnapshot;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

/**
 * 锁定描边调度器（FBO + 后处理，描边已集成到实体 render）：
 * - AFTER_SKY：确保 FBO 存在；清空（供实体渲染阶段由 {@link LockOutlineBufferSource} 双写填充）。
 * - AFTER_ENTITIES：flush 填充缓冲到 FBO → 全屏后处理（NDC quad 采样 FBO 做边缘检测 + 发光）。
 *
 * <p><b>渲染目标归属（黑屏的那一类根因，务必看清）</b>：1.20.1 的
 * {@code RenderTarget.clear(boolean)} <b>不是</b>「清自己」：它内部 {@code bindWrite(true)}（顺带把
 * <b>全局 viewport</b> 改成自己的尺寸）→ {@code glClear} → {@code unbindWrite()}，
 * 结束时 GL 的 draw framebuffer 停在 <b>0 号默认帧缓冲</b>（{@code RenderTarget.java:284-296}）。
 * 世界画面是从<b>主渲染目标</b> blit 出去的，画到 0 号上等于什么都没画。所以本类的每一段都遵守一条不变量：
 * <b>任何一段结束时，渲染目标必须是主渲染目标、viewport 必须是主目标的尺寸</b> —— 用
 * {@link LockOutlineFramebuffer#clearAndRestoreMain(Minecraft)} / {@link LockOutlineFramebuffer#bindMain(Minecraft)}
 * 显式完成（后者用 {@code bindWrite(true)}，因为 viewport 已被 clear 改脏）。
 * 每一段入口都会做一次（限流的）不变量核对，一旦发现"入口时绑定/尺寸/裁剪区不是主目标"就打一条 warn ——
 * 这是把「谁把渲染目标留在了自己身上」钉死的那条证据。</p>
 *
 * <p><b>与"模型几何镜像"（{@code ModelMirrorRenderer}）同阶段共存</b>：两者都挂在
 * {@code AFTER_ENTITIES}，先后顺序取决于事件总线的注册顺序（镜像在 {@code ShaderRenderRegistrar}，
 * 本类在 {@code LockOnClientEvents}，前者先注册先调用）。因此本类<b>不依赖</b>顺序：</p>
 * <ol>
 *   <li>入口快照会记录当时的 FBO（{@link RenderStateSnapshot#capture()}），只在"自己切过"或
 *       "确实被别人留脏"时才纠正，且永不还原到 0 号；</li>
 *   <li>fill 的 flush 与 post 的绘制都<b>显式</b>把主目标绑回去（不依赖上一个 pass 的收尾）。</li>
 * </ol>
 *
 * 通用描边：任意实体实现 {@code OutlineEntity} 或经 {@link OutlineRegistry#register} 注册 Provider 即自动描边，
 * 锁定系统在此注册为动态 Provider（青色，透明度=充能进度）。
 */
public final class LockOutlineRenderer {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("YizModelMirror");

    /** 描边屏幕像素半径（远近一致），全局可配置。 */
    private static int postRadius = 3;
    /** 高斯宽度：越大描边越细越硬，越小发光晕越宽，全局可配置。 */
    private static float edgeSharpness = 6.0F;

    private static LockOutlineFramebuffer framebuffer;

    static {
        // 通用描边接入：锁定系统作为动态 Provider（青色，透明度=充能进度）
        OutlineRegistry.register(LockOutlineRenderer::lockOutlineColor);
    }

    private LockOutlineRenderer() {}

    /** 设置全局描边屏幕像素半径（供 {@code EntityOutline} 公开 API 调用）。 */
    public static void setPostRadius(int px) { postRadius = Math.max(1, px); }

    /** 设置全局发光锐度（越大越细越硬，越小发光晕越宽）。 */
    public static void setEdgeSharpness(float s) { edgeSharpness = Math.max(0.5f, s); }

    /** 锁定系统描边色：当前玩家锁定的目标返回青色 [0,1,1,charge]，否则 null。 */
    private static float[] lockOutlineColor(Entity entity) {
        var mc = Minecraft.getInstance();
        if (mc.player == null) return null;
        TargetFrameProvider p = TargetFrameManager.getBest(mc.player);
        if (p == null || p.getTarget(mc.player) != entity) return null;
        float charge = p.getCharge();
        if (charge <= 0) return null;
        return new float[]{0.0f, 1.0f, 1.0f, charge};
    }

    /** 供 fill RenderType 的 FILL_TARGET 绑定 FBO。 */
    static LockOutlineFramebuffer framebuffer() { return framebuffer; }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        var stage = event.getStage(); // Stage 是 final class 非枚举，不能用 switch
        if (stage == RenderLevelStageEvent.Stage.AFTER_SKY) {
            // 实体渲染循环之前：确保 FBO 存在（实体渲染阶段 LockOutlineBufferSource 可能双写 fill 需要它）。
            // 无条件清空 FBO 供填充：星级描边是无条件渲染（不依赖渴攻锁定/充能），不能 gate 在锁定状态上。
            ensureFramebuffer(mc);
            // clear() 结束在 0 号帧缓冲 + viewport=FBO 尺寸 → 成对交还主目标（含 viewport）。
            framebuffer.clearAndRestoreMain(mc);
            diagInvariants(mc, "after-sky");
        } else if (stage == RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            if (framebuffer == null) return;
            // flush 填充缓冲到 FBO（fill RenderType 的 FILL_TARGET 绑 FBO）；无条件清空缓冲
            LockOutlineBufferSource.get(mc.renderBuffers().bufferSource()).endFillBatch();
            // fill 的 clearRenderState 已经把目标切回主目标，这里显式再来一次（防第三方 RenderType 收尾不对称）
            LockOutlineFramebuffer.bindMain(mc);
            // 本帧渲染过任意描边实体（星级/渴攻 Provider 返回非 null）才做后处理——
            // 星级描边无条件触发，渴攻描边透明度仍由 Provider 的 charge alpha 控制
            if (!LockOutlineBufferSource.consumeHasOutline()) return;
            framebuffer.resizeToMainTarget(mc);
            int w = framebuffer.getWidth(), h = framebuffer.getHeight();
            if (w <= 0 || h <= 0) return;
            renderPost(mc, w, h);
        }
    }

    private static void ensureFramebuffer(Minecraft mc) {
        // 尺寸跟主渲染目标（不是窗口）：窗口缩放/全屏那一帧两者不同，用窗口尺寸会让 post 的
        // 贴图分辨率/texelSize 与屏幕错位，并把 viewport 设成错的尺寸。
        int w = Math.max(1, mc.getMainRenderTarget().width);
        int h = Math.max(1, mc.getMainRenderTarget().height);
        if (framebuffer == null) {
            framebuffer = new LockOutlineFramebuffer(w, h);
        } else {
            framebuffer.resizeToMainTarget(mc);
        }
    }

    private static void renderPost(Minecraft mc, int w, int h) {
        Matrix4f savedProj = new Matrix4f(RenderSystem.getProjectionMatrix());
        VertexSorting savedSorting = RenderSystem.getVertexSorting();
        PoseStack mvStack = RenderSystem.getModelViewStack();
        // ── 全局状态快照 → try/finally 全量还原 ──
        // 后处理把 0 号纹理单元设成「描边 FBO 的颜色纹理」当 Sampler0（全屏 quad 采样它做边缘检测）。
        // 那个 FBO 每帧在 AFTER_SKY 被清成 (0,0,0,0)，也就是说：**不还原 = 之后所有"程序里有 Sampler0、
        // 自己又没设贴图"的 draw 都会采到一张纯黑贴图**。这是典型的"整屏发黑且不会自愈"
        // （1.20.1 的 RenderSystem.setShaderTexture 只写内部数组，脏值会被之后每一次 draw 继续使用）。
        // 而且它和体表特效 pass 同在 AFTER_ENTITIES，先后顺序怎么排都会互相看到对方留下的值 ——
        // 所以这里按"进来一份、出去一份"整份还回，不手写单项。
        RenderStateSnapshot snapshot = RenderStateSnapshot.capture();
        // 本 pass 会自己切目标（显式绑主目标）→ 声明所有权：退出时交还主目标，而不是"入口抓到的那个值"
        // （入口可能是别的 pass 留下的、甚至 0 号帧缓冲）。
        snapshot.ownFramebuffer();
        mvStack.pushPose();
        mvStack.setIdentity();
        RenderSystem.applyModelViewMatrix();

        try {
            // 单位投影 + NDC 全屏 quad（顶点 -1..1，z=0 必然通过裁剪，绕开 ortho z 语义问题）
            RenderSystem.setProjectionMatrix(new Matrix4f(), VertexSorting.ORTHOGRAPHIC_Z);

            // 采样源 = 描边 FBO 的颜色纹理；**绘制目标必须显式绑回主目标**：
            // 若 FBO 仍是当前绘制目标，采样它就构成 GL 的 feedback loop（同一贴图既是采样源又是渲染目标），
            // 结果未定义（常见整屏黑/花屏）。这一行让"post 画到哪"不再依赖任何前序 pass 的收尾。
            LockOutlineFramebuffer.bindMain(mc);
            diagInvariants(mc, "lock-post");

            RenderSystem.setShaderTexture(0, framebuffer.getColorTextureId());
            ShaderInstance shader = LockOutlineShaders.getLockPost();
            if (shader != null) {
                if (shader.getUniform("uRadius") != null)
                    shader.getUniform("uRadius").set((float) postRadius);
                if (shader.getUniform("uSharpness") != null)
                    shader.getUniform("uSharpness").set(edgeSharpness);
                if (shader.getUniform("uTexelSize") != null)
                    shader.getUniform("uTexelSize").set(1.0f / w, 1.0f / h);
            }

            // NDC 全屏 quad（POSITION_TEX）：clip = 顶点坐标，覆盖全屏；UV v=0 底部对应 FBO 底部
            RenderType postRt = LockOutlineRenderType.post();
            var vc = mc.renderBuffers().bufferSource().getBuffer(postRt);
            vc.vertex(-1, -1, 0).uv(0, 0).endVertex();
            vc.vertex(1, -1, 0).uv(1, 0).endVertex();
            vc.vertex(1, 1, 0).uv(1, 1).endVertex();
            vc.vertex(-1, 1, 0).uv(0, 1).endVertex();
            mc.renderBuffers().bufferSource().endBatch(postRt);
        } finally {
            mvStack.popPose();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(savedProj, savedSorting);
            // 纹理单元（含 0 号那个"描边 FBO 黑图"）+ 着色器 + 着色器颜色 + FBO 绑定 + viewport
            // + 混合/剔除/深度掩码一起还回
            snapshot.restore("lock-post");
        }
    }

    /**
     * 渲染目标不变量核对（限流）：本类的四段都只允许在<b>主渲染目标 + 主目标 viewport</b>下收尾。
     *
     * <p>为什么要这条日志：黑屏那类问题的两种形态（"画到了 0 号帧缓冲"、"viewport 是描边 FBO 的尺寸"）
     * 都不会抛异常、也不会自己恢复，只在画面上表现为整屏黑/错位。这里在每段入口读一次 GL 真值，
     * 一旦发现不一致就留下一条 warn（含 FBO id / 尺寸 / viewport），下一次复现时可以直接判死。</p>
     */
    private static void diagInvariants(Minecraft mc, String where) {
        int bound = GlStateManager.getBoundFramebuffer();
        int main = mc.getMainRenderTarget().frameBufferId;
        int[] vp = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, vp);
        int mw = mc.getMainRenderTarget().width, mh = mc.getMainRenderTarget().height;
        int fw = framebuffer != null ? framebuffer.getWidth() : -1;
        int fh = framebuffer != null ? framebuffer.getHeight() : -1;
        // 只报"确实异常"：目标不是主目标 / viewport 与主目标尺寸不符 / 描边 FBO 与主目标尺寸不符
        String bad = null;
        if (bound != main) bad = "渲染目标不是主目标";
        else if (vp[2] != mw || vp[3] != mh) bad = "viewport 与主目标尺寸不符";
        else if (framebuffer != null && (fw != mw || fh != mh)) bad = "描边 FBO 与主目标尺寸不符";
        if (bad == null) return;
        // 限流：同一"位置+异常种类"只打一条（借 MirrorDiag 的 first(key)，不额外维护状态）
        if (!net.minecraft.client.yiz.modelmirror.MirrorDiag.first("lock:invariant:" + where + ":" + bad)) return;
        LOGGER.warn(
                "[LockOutline] 阶段={} 检测到渲染目标不变量异常: {}（当前FBO={} 主目标FBO={} viewport={}x{}+{}+{} "
                        + "主目标={}x{} 描边FBO={}x{}）—— 这一项异常会让本阶段之后的绘制落到错误的缓冲/错误的裁剪区，"
                        + "表现就是整屏黑或画面错位，且不会自愈",
                where, bad, bound, main, vp[2], vp[3], vp[0], vp[1], mw, mh, fw, fh);
    }
}
