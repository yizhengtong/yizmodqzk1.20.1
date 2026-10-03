package net.minecraft.client.yiz;

import net.minecraft.client.yiz.api.ShaderProtectionRegistry;
import net.minecraft.client.yiz.lightning.render.LightningShaders;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterShadersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.IOException;

/**
 * 前置库着色器注册（1.20.1 修正版）。
 *
 * <p><b>根因修复（2026-08-26）</b>：1.20.1 的 {@link RegisterShadersEvent} 由
 * {@code GameRenderer.loadShaders} 内 {@code ModLoader.postEvent} 触发 —— 走 <b>MOD 总线</b>
 * （此前误挂 FORGE/game 总线导致所有自定义 shader 未加载、特效不显示）。故本类挂 {@code Bus.MOD}。</p>
 *
 * <p>ClientTick/RenderLevelStage 等 game 总线事件由 {@link ShaderRenderRegistrar} 处理。</p>
 */
@Mod.EventBusSubscriber(modid = tizMod.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ShaderEventRegistrar {

    private ShaderEventRegistrar() {}

    @SubscribeEvent
    public static void onRegisterShaders(RegisterShadersEvent event) throws IOException {
        // 先声明（纯登记，不碰 event），让保护注册表统一注册并做 Iris 兼容；
        // 实例由 FlameShaders.flame() 在首帧惰性取用（事件期间 registerShader 只是排队）
        net.minecraft.client.yiz.flame.render.FlameShaders.declareProtected();
        // 贴模型火焰（"着火"贴实体实际模型）：同一套声明式注册，单独一个 shader + RenderType
        net.minecraft.client.yiz.flame.render.FlameSurfaceShaders.declareProtected();
        // 注：编号 4 围弧（第六轮起）不再有自己的着色器 —— 它复用编号 1 的
        // rendertype_lightning_arc（见 ShockArcs 类注释）。原先的 rendertype_shock_surface.*
        // 与 ShockSurfaceShaders 已随"几何=皮肤顶点"那条路整体删除。
        ShaderProtectionRegistry.onRegisterShaders(event);

        LightningShaders.onRegisterShaders(event);
        net.minecraft.client.yiz.client.render.LockOutlineShaders.onRegisterShaders(event);
    }
}
