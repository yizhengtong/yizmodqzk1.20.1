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
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;

/**
 * 锁定描边双写缓冲（复刻 vanilla {@code OutlineBufferSource}）：
 * 当前渲染实体需要描边时，一次渲染同时写主缓冲（正常显示）+ 填充 FBO（穿墙纯色）。
 * fill RenderType 带实体纹理（纹理过滤）+ 绑 {@link LockOutlineFramebuffer}。
 * 由 {@code EntityRenderDispatcherOutlineMixin} 在实体渲染时 {@link #setCurrent} 驱动。
 */
public final class LockOutlineBufferSource implements MultiBufferSource {

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
        if (currentEntity != null && currentTexture != null && currentOutline != null) {
            // 一次渲染双写：主缓冲正常 + fill 到 FBO（穿墙，顶点色覆盖为实体描边色）
            VertexConsumer fill = new FillColorConsumer(
                fillBufferSource.getBuffer(LockOutlineRenderType.fill(currentTexture)), currentOutline);
            return VertexMultiConsumer.create(fill, normal);
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
