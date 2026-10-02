package net.minecraft.client.yiz;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 前置库 game 总线事件注册（1.20.1）— 客户端 tick / 世界渲染。
 *
 * <p>1.20.1 中 ClientTickEvent/RenderLevelStageEvent 由
 * {@code MinecraftForge.EVENT_BUS}（game 总线）触发，挂 {@code Bus.FORGE}。
 * 着色器注册（RegisterShadersEvent）走 MOD 总线，见 {@link ShaderEventRegistrar}。</p>
 */
@Mod.EventBusSubscriber(modid = tizMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class ShaderRenderRegistrar {

    private ShaderRenderRegistrar() {}

    /** 客户端每 tick：感电状态衰减 + 闪电特效 tick。 */
    @SubscribeEvent
    public static void onClientTick(net.minecraftforge.event.TickEvent.ClientTickEvent event) {
        if (event.phase != net.minecraftforge.event.TickEvent.Phase.END) return;
        net.minecraft.client.yiz.api.ShockedEntityAPI.tick();
        // 围绕实体的游离弧（模型几何镜像的 AIR_ARC 层）状态衰减：与体表感电同构，但寿命短得多
        net.minecraft.client.yiz.lightning.render.LightningAirArcState.tick();
        net.minecraft.client.yiz.lightning.render.LightningRenderer.tick();
        net.minecraft.client.yiz.flame.FlameEffects.tick();
    }

    /** 世界渲染：闪电电弧/球/spark 绘制。 */
    @SubscribeEvent
    public static void onRenderLevelStage(net.minecraftforge.client.event.RenderLevelStageEvent event) {
        net.minecraft.client.yiz.lightning.render.LightningRenderer.onRenderLevelStage(event);
        net.minecraft.client.yiz.flame.render.FlameRenderer.onRenderLevelStage(event);
        // 模型几何镜像（贴模型火焰 + 体表闪电）在 AFTER_ENTITIES 刷帧：必须在实体本体画完之后
        // （理由见 ModelMirrorRenderer 类注释）
        net.minecraft.client.yiz.modelmirror.ModelMirrorRenderer.onRenderLevelStage(event);
    }
}
