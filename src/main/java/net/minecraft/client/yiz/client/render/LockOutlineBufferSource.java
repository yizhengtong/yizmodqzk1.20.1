package net.minecraft.client.yiz.client.render;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultedVertexConsumer;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexMultiConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.yiz.api.OutlineRegistry;
import net.minecraft.client.yiz.modelmirror.MirrorDiag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 锁定描边双写缓冲（复刻 vanilla {@code OutlineBufferSource}）：
 * 当前渲染实体需要描边时，一次渲染同时写主缓冲（正常显示）+ 填充 FBO（穿墙纯色）。
 * fill RenderType 带实体纹理（纹理过滤）+ 绑 {@link LockOutlineFramebuffer}。
 * 由 {@code EntityRenderDispatcherOutlineMixin} 在实体渲染时 {@link #setCurrent} 驱动。
 *
 * <p><b>与 vanilla 的三处对齐</b>（都很关键，理由写在各处）：</p>
 * <ol>
 *   <li>只双写 {@code rt.outline().isPresent()}（= affects_outline）的 RenderType：影子/眼睛/贴花等
 *       NONE 层不能进填充 FBO，否则描边会沿着"地上的影子方块"等非体表几何发光；</li>
 *   <li>fill consumer 的 {@code defaultColor}/{@code unsetDefaultColor} 是空实现，描边色不会被
 *       实体自身默认色覆盖；</li>
 *   <li>fill 只在 {@code MultiBufferSource.BufferSource} 语义下攒批，flush 由
 *       {@link #endFillBatch()} 驱动（fill RenderType 的 FILL_TARGET 负责"绑 FBO/交还主目标"）。</li>
 * </ol>
 */
public final class LockOutlineBufferSource implements MultiBufferSource {

    private static final Logger LOGGER = LoggerFactory.getLogger("YizModelMirror");

    private static LockOutlineBufferSource INSTANCE;

    /** 本帧是否渲染过任意描边实体（星级/渴攻等任意 Provider 返回非 null 即置位）：
     *  供 LockOutlineRenderer 后处理判断，实现星级描边无条件渲染（不依赖渴攻锁定/充能）。 */
    private static boolean hasAnyOutline;

    // fill buffer 容量加大：复杂实体（GeckoLib 辖界者等）顶点上千，256 初始容量会在实体渲染中途
    // 自动 endBatch → FILL_TARGET 绑 FBO 刷帧（配 clearTask 切回主缓冲双保险）。
    private final MultiBufferSource.BufferSource fillBufferSource = MultiBufferSource.immediate(new BufferBuilder(65536));
    private MultiBufferSource.BufferSource mainBufferSource;
    private Entity currentEntity;
    private ResourceLocation currentTexture;
    private float[] currentOutline;

    private LockOutlineBufferSource() {}

    /** 获取/复用实例并绑定主缓冲。 */
    public static LockOutlineBufferSource get(MultiBufferSource.BufferSource main) {
        if (INSTANCE == null) INSTANCE = new LockOutlineBufferSource();
        INSTANCE.mainBufferSource = main;
        return INSTANCE;
    }

    /** mixin 在实体渲染前调用：记录当前实体、其主纹理与描边参数（供 fill 双写）。 */
    public static void setCurrent(Entity entity) {
        if (INSTANCE != null) {
            INSTANCE.currentEntity = entity;
            INSTANCE.currentTexture = textureOf(entity);
            INSTANCE.currentOutline = entity != null ? OutlineRegistry.getOutlineColor(entity) : null;
            if (INSTANCE.currentOutline != null) hasAnyOutline = true;
        }
    }

    /** 消费本帧是否有描边实体（AFTER_ENTITIES 后处理判断用；读后清标记）。 */
    public static boolean consumeHasOutline() {
        boolean b = hasAnyOutline;
        hasAnyOutline = false;
        return b;
    }

    public static void clearCurrent() {
        if (INSTANCE != null) INSTANCE.currentEntity = null;
    }

    /** 当前渲染的实体是否需要描边（mixin @ModifyArg 单参数 handler 判断用）。 */
    public static boolean isCurrentOutlined() {
        return INSTANCE != null && INSTANCE.currentEntity != null && INSTANCE.currentOutline != null;
    }

    /** flush 填充缓冲到 FBO（fill RenderType 的 FILL_TARGET 绑 FBO）。 */
    public void endFillBatch() {
        fillBufferSource.endBatch();
    }

    @Override
    public VertexConsumer getBuffer(RenderType rt) {
        VertexConsumer normal = mainBufferSource.getBuffer(rt);
        // ── 门禁（与 vanilla {@code OutlineBufferSource.getBuffer} 一字不差，{@code OutlineBufferSource.java:24-39}）──
        // 只有 {@code outlineProperty == AFFECTS_OUTLINE} 的 RenderType 才参与描边填充：vanilla 的判据就是
        // {@code rt.outline().isPresent()}（{@code RenderType.java:459} + {@code :495}，NONE 的返回 empty）。
        // 漏掉这道门禁会出两类错几何：① 影子（{@code entity_shadow} = createCompositeState(false) → NONE）
        // 是<b>贴地</b>的一整片四边形，被填进 FBO 后描边会沿着"地上的影子方块"发光；
        // ② 眼睛/贴花/充能漩涡（eyes / entity_decal / energy_swirl，同样 NONE）等非体表层。
        // 而且这些层的贴图/UV 与体表不同，填进 FBO 会让后处理的"邻域填充密度"算出一片乱纹
        // （density≈0.5 的区域会整片变成本层的填充色，是整屏被"糊上一层色"的直接来源）。
        if (currentEntity != null && currentTexture != null && currentOutline != null
                && rt != null && rt.outline().isPresent()) {
            // 一次渲染双写：主缓冲正常 + fill 到 FBO（穿墙，顶点色覆盖为实体描边色）
            VertexConsumer fill = new FillColorConsumer(
                fillBufferSource.getBuffer(LockOutlineRenderType.fill(currentTexture)), currentOutline);
            return VertexMultiConsumer.create(fill, normal);
        }
        if (currentEntity != null && currentOutline != null && rt != null
                && MirrorDiag.first("lock:skipOutlineType:" + MirrorDiag.entity(currentEntity) + ":" + rt)) {
            LOGGER.info("[LockOutline] 该 RenderType 不参与描边填充（非 affects_outline 层，与 vanilla 一致）: "
                            + "entity={} renderType={}",
                    MirrorDiag.entity(currentEntity), rt);
        }
        return normal;
    }

    /** 覆盖顶点色为描边色的 fill consumer（复刻 vanilla EntityOutlineGenerator，保留全部 NEW_ENTITY 属性）。 */
    private static final class FillColorConsumer extends DefaultedVertexConsumer {
        private final VertexConsumer delegate;
        private double x, y, z;
        private float u, v;
        private int overlayU, overlayV, lightU, lightV;
        private float nx, ny, nz;

        FillColorConsumer(VertexConsumer delegate, float[] color) {
            this.delegate = delegate;
            this.defaultColor(
                (int) (color[0] * 255f), (int) (color[1] * 255f),
                (int) (color[2] * 255f), (int) (color[3] * 255f));
        }

        /**
         * <b>必须屏蔽</b>：{@code VertexMultiConsumer} 会把 {@code defaultColor(...)} 广播给每个下游
         * consumer（{@code VertexMultiConsumer.java:85-93}），而 {@code DefaultedVertexConsumer.defaultColor}
         * 会覆盖构造器里设好的 {@code defaultR/G/B/A} —— 于是"描边色"会被实体自己的默认色冲掉
         * （vanilla 的 {@code EntityOutlineGenerator} 同样把这两个方法写成空实现，
         * {@code OutlineBufferSource.java:66-70}，理由一致）。描边色只能来自本构造函数那一次设置。
         */
        @Override
        public void defaultColor(int r, int g, int b, int a) {}

        /** 同上：屏蔽"清默认色"（否则 endVertex 写出的颜色仍是构造器那份，语义上不该被外部改）。 */
        @Override
        public void unsetDefaultColor() {}

        @Override
        public VertexConsumer vertex(double x, double y, double z) { this.x = x; this.y = y; this.z = z; return this; }
        @Override
        public VertexConsumer color(int r, int g, int b, int a) { return this; } // 忽略原色，用描边色
        @Override
        public VertexConsumer uv(float u, float v) { this.u = u; this.v = v; return this; }
        @Override
        public VertexConsumer overlayCoords(int u, int v) { this.overlayU = u; this.overlayV = v; return this; }
        @Override
        public VertexConsumer uv2(int u, int v) { this.lightU = u; this.lightV = v; return this; }
        @Override
        public VertexConsumer normal(float x, float y, float z) { this.nx = x; this.ny = y; this.nz = z; return this; }

        @Override
        public void endVertex() {
            delegate.vertex(x, y, z)
                .color(defaultR, defaultG, defaultB, defaultA)
                .uv(u, v).overlayCoords(overlayU, overlayV).uv2(lightU, lightV).normal(nx, ny, nz)
                .endVertex();
        }
    }

    private static ResourceLocation textureOf(Entity entity) {
        try {
            EntityRenderer er = (EntityRenderer) Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(entity);
            return er != null ? er.getTextureLocation(entity) : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
