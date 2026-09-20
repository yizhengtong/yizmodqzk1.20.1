package net.minecraft.client.yiz.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.yiz.render.LaunchTiltTracker;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 击飞后仰姿态渲染。
 *
 * <p>在 {@code setupRotations} 之后叠加：此时 poseStack 已按实体朝向旋转到位、原点在实体脚下，
 * 因此绕局部 X 轴旋转即等于"以脚为支点向后倒"，不会受实体朝向影响。</p>
 *
 * <p>若实机观察发现是"向前趴"而不是"向后倒"，把下面的负号去掉即可。</p>
 */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererLaunchTiltMixin {

    @Inject(
        method = "setupRotations(Lnet/minecraft/world/entity/LivingEntity;Lcom/mojang/blaze3d/vertex/PoseStack;FFF)V",
        at = @At("TAIL")
    )
    private void yizmodqzk$launchTilt(LivingEntity entity, PoseStack poseStack, float ageInTicks,
                                      float rotationYaw, float partialTicks, CallbackInfo ci) {
        float tilt = LaunchTiltTracker.tiltOf(entity, partialTicks);
        if (tilt <= 0.0F) return;
        // 正号 = 仰面平躺（向后倒，从站立变成平躺）。原版把实体放平用的是
        // PlayerRenderer 游泳姿态的 Axis.XP.rotationDegrees(-90)，那是「俯卧（脸朝下）」；
        // 向后倒取相反符号。若实机看到的是脸朝下，这里改成 -tilt。
        poseStack.mulPose(Axis.XP.rotationDegrees(tilt));
    }
}
