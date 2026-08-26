package net.minecraft.client.yiz;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.yiz.api.ShaderManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.TextureStitchEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 前置库 game 总线事件注册（1.20.1）— 客户端 tick / 世界渲染 / 图集缝合。
 *
 * <p>1.20.1 中 ClientTickEvent/RenderLevelStageEvent/TextureStitchEvent 由
 * {@code MinecraftForge.EVENT_BUS}（game 总线）触发，挂 {@code Bus.FORGE}。</p>
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

    /** 方块图集缝合后：把 cosmic_0..9 图标 UV 写入 ShaderManager，喂给 cosmic3 系。 */
    @SubscribeEvent
    public static void onAtlasStitched(TextureStitchEvent.Post event) {
        float[] uvs = new float[40];
        for (int i = 0; i < 10; i++) {
            ResourceLocation loc = new ResourceLocation(tizMod.MODID, "cosmic/cosmic_" + i);
            TextureAtlasSprite sprite = event.getAtlas().getSprite(loc);
            uvs[i * 4]     = sprite.getU0();
            uvs[i * 4 + 1] = sprite.getV0();
            uvs[i * 4 + 2] = sprite.getU1();
            uvs[i * 4 + 3] = sprite.getV1();
        }
        ShaderManager.setCosmicUVs(uvs);
    }
}
