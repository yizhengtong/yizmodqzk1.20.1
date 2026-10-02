package net.minecraft.client.yiz.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.yiz.modelmirror.MirrorDiag;
import net.minecraft.client.yiz.modelmirror.ModelMirrorBufferSource;
import net.minecraft.world.entity.Entity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 把"模型几何镜像"接进实体渲染：所有实体渲染都汇聚到
 * {@code EntityRenderDispatcher.render(Entity, ...)}，在这里把实体渲染用的 {@link MultiBufferSource}
 * 换成"多路复用双写"版本（{@link ModelMirrorBufferSource}），于是实体模型几何被多抄若干份，
 * 交给各消费者（贴模型火焰 / 体表闪电）分别画出来 —— 几何贴的是模型本身，不是碰撞箱。
 *
 * <p><b>为什么注入点选这里</b>：</p>
 * <ul>
 *   <li>它在实体渲染器（{@code LivingEntityRenderer} / {@code ItemEntityRenderer} / ...）之外，
 *       所以对"生物模型"和"其它带模型的实体"一视同仁，且拿得到渲染位置与朝向（uniform 需要）；</li>
 *   <li>它和描边管线（{@code EntityRenderDispatcherOutlineMixin}）用的是同一个汇聚点，但注入方式不同：
 *       描边改的是<b>调用实参</b>（{@code @ModifyArg}），本类改的是<b>方法形参</b>
 *       （{@code @ModifyVariable(argsOnly = true)}）。形参改写一定发生在调用之前，于是本类的包装
 *       永远在描边包装的<b>内层</b>：描边 args 判断拿到的仍是真正的 BufferSource，行为一字不变；</li>
 *   <li>本类不做任何形参侧的状态判断（{@code wrap} 无条件换成单例），真正的"这个实体有哪些层激活"
 *       在写顶点时惰性查询 —— 因此与同方法上的其它 HEAD 注入之间没有先后顺序依赖。</li>
 * </ul>
 *
 * <p>不选 {@code LivingEntityRenderer.render} 的 popPose 注入点（体表闪电旧实现那套）的原因：
 * 那里要把模型<b>再渲染一遍</b>（动画/骨骼/GeckoLib 全部重算一次，且只覆盖 getModel() 那一层），
 * 而双写只是多拷一份顶点，且覆盖所有本体 pass。</p>
 */
@Mixin(net.minecraft.client.renderer.entity.EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherModelMirrorMixin {

    /** 临时诊断用（定位"贴模型效果完全看不见"）。 */
    private static final Logger LOGGER = LoggerFactory.getLogger("YizModelMirror");

    private static final String RENDER_DESC =
        "render(Lnet/minecraft/world/entity/Entity;DDDFFLcom/mojang/blaze3d/vertex/PoseStack;"
        + "Lnet/minecraft/client/renderer/MultiBufferSource;I)V";

    /** 实体渲染前：记录当前实体与它相对相机的渲染位置/朝向（供各消费者的 uniform 用）。 */
    @Inject(method = RENDER_DESC, at = @At("HEAD"))
    private void yizmodqzk$modelMirrorBegin(Entity entity, double x, double y, double z, float yaw, float ptt,
            PoseStack ps, MultiBufferSource buffer, int light, CallbackInfo ci) {
        String status = ModelMirrorBufferSource.begin(entity, x, y, z, yaw);
        // ── 临时诊断（定位"贴模型效果完全看不见"）：本实体有没有真的走到实体渲染、传进来的是什么缓冲源、
        //    以及 begin() 的几道闸门（透视判据 / 是否存在激活层）各自的结果。每个实体只打一次。──
        if (MirrorDiag.first("mm:begin:" + MirrorDiag.entity(entity))) {
            LOGGER.info("[ModelMirror] 进入渲染: entity={} 传入 MultiBufferSource 实际类={}（是否 BufferSource={} / 是否本双写源={}）→ {}",
                    MirrorDiag.entity(entity),
                    buffer == null ? "null" : buffer.getClass().getName(),
                    buffer instanceof MultiBufferSource.BufferSource,
                    buffer == ModelMirrorBufferSource.instance(),
                    status);
        }
    }

    /** 实体渲染结束：恢复外层状态（批次留到 AFTER_ENTITIES 统一刷帧）。 */
    @Inject(method = RENDER_DESC, at = @At("RETURN"))
    private void yizmodqzk$modelMirrorEnd(CallbackInfo ci) {
        ModelMirrorBufferSource.end();
    }

    /**
     * 换主缓冲：<b>任何</b> {@link MultiBufferSource} 都会被包（早先只包 BufferSource，
     * 于是原版队伍发光的 {@code OutlineBufferSource} 等非 BufferSource 场景整条静默失效）；
     * 包出来的单例自身是 BufferSource（描边那处 {@code instanceof BufferSource} 判断因此不受影响），
     * 并把 endBatch/endLastBatch 在 delegate 恰好是 BufferSource 时原样转发回真主缓冲。
     */
    @ModifyVariable(method = RENDER_DESC, at = @At("HEAD"), argsOnly = true)
    private MultiBufferSource yizmodqzk$modelMirrorBuffer(MultiBufferSource buffer) {
        return ModelMirrorBufferSource.wrap(buffer);
    }
}
