package net.minecraft.client.yiz.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.yiz.anim.client.AnimSkeletonCapture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在 {@link ModelPart#render(PoseStack, VertexConsumer, int, int, float, float, float, float)} 的
 * HEAD 把"父链变换 + 本骨骼 translateAndRotate"的最终矩阵与真实 cube 几何交给
 * {@link AnimSkeletonCapture}。
 *
 * <p>选 HEAD 的原因：原版这个方法的实现是 {@code pushPose(); translateAndRotate(); compile(...);}
 * —— HEAD 时 pose 栈顶正好是本骨骼的父链矩阵（GUI 相机 × 实体渲染内部变换 × 父骨骼），
 * 本骨骼自己的平移/旋转/缩放还没进栈；捕获类按原版顺序补齐这三步即可拿到和 GPU 完全一致的矩阵。
 * 4 参数的 {@code render} 会转调这个 8 参数版本，所以两条调用路径都能捕到。</p>
 *
 * <p>非视口时间 {@link AnimSkeletonCapture#onPartRender} 会立即返回，不会产生额外开销。</p>
 */
@Mixin(ModelPart.class)
public abstract class ModelPartRenderCaptureMixin {

    @Inject(
            method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;IIFFFF)V",
            at = @At("HEAD"))
    private void yizmodqzk$captureSkeleton(PoseStack poseStack, VertexConsumer buffer, int packedLight,
                                           int packedOverlay, float red, float green, float blue, float alpha,
                                           CallbackInfo ci) {
        AnimSkeletonCapture.onPartRender((ModelPart) (Object) this, poseStack);
    }
}
