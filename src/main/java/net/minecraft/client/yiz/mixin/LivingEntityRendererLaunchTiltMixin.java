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
 * 击飞后仰姿态渲染 —— <b>本 mixin 只做这一件事</b>。
 *
 * <p>在 {@code setupRotations} 的 TAIL 叠加：此时 poseStack 已按实体朝向旋转到位、原点在实体脚下，
 * 因此绕局部 X 轴旋转即等于"以脚为支点向后倒"，不会受实体朝向影响。</p>
 *
 * <p>若实机观察发现是"向前趴"而不是"向后倒"，把下面的负号去掉即可。</p>
 *
 * <h2>⚑ 这里<b>不再</b>对外公布姿态量 / 模型矩阵（第四轮删除，别再接线回来）</h2>
 * <p>为让"世界坐标下的特效"复算模型姿态，这里曾经做过两件事，都已随
 * {@code ModelTransformTracker} 整体删除：</p>
 * <ol>
 *   <li><b>抄整份 {@code poseStack.last().pose()}</b>：那个矩阵的输出空间是「相机相对」而不是世界
 *       （实体 pass 的 poseStack 已经 {@code translate(插值位置 − 相机)}），用它换算世界特效必须先
 *       补回相机；而且它<b>只在该实体本帧真的被渲染时才存在</b>，视锥剔除/太远时没有记录
 *       ⇒ 行为随视角跳变。实测两次"看不见"。</li>
 *   <li><b>只公布两个标量</b>（{@code rotationYaw} + 插值原点）：本工程实测仍然"偏移到姥姥家"，
 *       因为"复算姿态"这条路无论抄多少个数，都只能<b>逼近</b>模型被渲染到的位置。</li>
 * </ol>
 * <p>正确做法见 {@code net.minecraft.client.yiz.modelmirror}（模型几何镜像）：特效的几何直接取
 * <b>模型自己写出的那批顶点</b>，于是"模型被渲染到哪里，特效就在哪里"是结构性的 ——
 * 既包括本 mixin 叠的击飞倾斜，也包括别的模组把模型整个挪走的变换，一个都不需要复算。
 * 围弧（编号 4）第六轮起就是这样接的：几何是独立的三维折线（飘在体外），
 * 端点取自模型顶点采出的锚点（{@code ShockArcs} + {@code ShockAnchors}）——
 * 所以它<b>同样吃得到本 mixin 叠的倾斜</b>（顶点被转过去了），却不需要复算任何姿态。</p>
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
