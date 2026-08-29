package net.minecraft.client.yiz.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.yiz.client.render.LockOutlineBufferSource;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 把描边集成到实体渲染：所有实体渲染经 {@code EntityRenderDispatcher.render} 汇聚。
 * 渲染前设置当前实体（{@link LockOutlineBufferSource#setCurrent}），
 * 需要描边的实体用 LockOutlineBufferSource 双写主缓冲 + 填充 FBO（一次渲染不重复）。
 */
@Mixin(net.minecraft.client.renderer.entity.EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherOutlineMixin {

    private static final String RENDER_DESC =
        "render(Lnet/minecraft/world/entity/Entity;DDDFFLcom/mojang/blaze3d/vertex/PoseStack;"
        + "Lnet/minecraft/client/renderer/MultiBufferSource;I)V";
    private static final String RENDERER_RENDER_TARGET =
        "Lnet/minecraft/client/renderer/entity/EntityRenderer;"
        + "render(Lnet/minecraft/world/entity/Entity;FFLcom/mojang/blaze3d/vertex/PoseStack;"
        + "Lnet/minecraft/client/renderer/MultiBufferSource;I)V";

    @Inject(method = RENDER_DESC, at = @At("HEAD"))
    private void yizmodqzk$outlineBefore(Entity entity, double x, double y, double z, float yaw, float ptt,
            PoseStack ps, MultiBufferSource buffer, int light, CallbackInfo ci) {
        LockOutlineBufferSource.setCurrent(entity);
    }

    @Inject(method = RENDER_DESC, at = @At("RETURN"))
    private void yizmodqzk$outlineAfter(CallbackInfo ci) {
        LockOutlineBufferSource.clearCurrent();
    }

    @ModifyArg(method = RENDER_DESC,
        at = @At(value = "INVOKE", target = RENDERER_RENDER_TARGET),
        index = 4)
    private MultiBufferSource yizmodqzk$outlineBuffer(MultiBufferSource replaced) {
        // 当前实体是否描边由 @Inject HEAD 的 setCurrent 决定（@ModifyArg 单参数形式拿不到方法参数）
        if (LockOutlineBufferSource.isCurrentOutlined() && replaced instanceof MultiBufferSource.BufferSource bs) {
            return LockOutlineBufferSource.get(bs);
        }
        return replaced;
    }
}
