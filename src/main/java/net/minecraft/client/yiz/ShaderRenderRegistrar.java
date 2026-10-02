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
        net.minecraft.client.yiz.lightning.render.LightningRenderer.tick();
    }

    /** 世界渲染：闪电电弧/球/spark 绘制。 */
    @SubscribeEvent
    public static void onRenderLevelStage(net.minecraftforge.client.event.RenderLevelStageEvent event) {
        net.minecraft.client.yiz.lightning.render.LightningRenderer.onRenderLevelStage(event);
    }
}
