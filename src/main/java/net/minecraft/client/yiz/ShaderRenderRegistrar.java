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

    /** 客户端每 tick：闪电特效 tick + 感电围弧状态 tick。 */
    @SubscribeEvent
    public static void onClientTick(net.minecraftforge.event.TickEvent.ClientTickEvent event) {
        if (event.phase != net.minecraftforge.event.TickEvent.Phase.END) return;
        net.minecraft.client.yiz.lightning.render.LightningRenderer.tick();
        // 围弧（编号 4）：① 感电状态（谁/多亮/还有多久）；② 弧自身的生灭（寿命、按屏幕覆盖率补生）。
        // 顺序不能反：ShockArcs.tick() 要读 ShockEffects 的当前状态来决定"这个实体还该不该有弧"，
        // 所以先推进状态、再推进弧。两者都在客户端主线程（= 渲染线程）。
        net.minecraft.client.yiz.lightning.fx.ShockEffects.tick();
        net.minecraft.client.yiz.lightning.fx.ShockArcs.tick();
        net.minecraft.client.yiz.flame.FlameEffects.tick();
    }

    /** 世界渲染：闪电电弧（编号 1 链式 + 编号 4 围弧共用同一个 pass）/ 贴模型火焰绘制。 */
    @SubscribeEvent
    public static void onRenderLevelStage(net.minecraftforge.client.event.RenderLevelStageEvent event) {
        // 闪电 pass 现在画两条队列：链式电弧（开关 1）与围弧（开关 4）。
        // 围弧的几何是独立折线、端点取自模型顶点采出的锚点（见 ShockArcs），
        // 与"模型几何镜像"是两条并列的下游 —— 围弧不再走镜像层，所以本 pass 与下面那行互不影响。
        net.minecraft.client.yiz.lightning.render.LightningRenderer.onRenderLevelStage(event);
        net.minecraft.client.yiz.flame.render.FlameRenderer.onRenderLevelStage(event);
        // 模型几何镜像（当前只有"贴模型火焰"一层）在 AFTER_ENTITIES 刷帧：必须在实体本体画完之后
        // （理由见 ModelMirrorRenderer 类注释）
        net.minecraft.client.yiz.modelmirror.ModelMirrorRenderer.onRenderLevelStage(event);
    }
}
