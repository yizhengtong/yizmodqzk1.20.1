package net.minecraft.client.yiz;

import net.minecraft.client.yiz.api.ShaderManager;
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
 * <p>ClientTick/RenderLevelStage/TextureStitch 等 game 总线事件由 {@link ShaderRenderRegistrar} 处理。</p>
 */
@Mod.EventBusSubscriber(modid = tizMod.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ShaderEventRegistrar {

    private ShaderEventRegistrar() {}

    @SubscribeEvent
    public static void onRegisterShaders(RegisterShadersEvent event) throws IOException {
        ShaderProtectionRegistry.onRegisterShaders(event);
        ShaderManager.onRegisterShaders(event);
        LightningShaders.onRegisterShaders(event);
        net.minecraft.client.yiz.client.render.LockOutlineShaders.onRegisterShaders(event);
    }
}
