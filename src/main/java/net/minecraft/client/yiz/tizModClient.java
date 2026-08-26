package net.minecraft.client.yiz;

import net.minecraft.client.yiz.api.ShaderManager;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/**
 * 前置库客户端入口（1.20.1 移植版，仅着色器相关）。
 *
 * <p>1.21.1 的 tizModClient 还含 HUD/UI/按键等大量系统（1.20.1 未移植，见 port-gap-list）。
 * 本类只注册着色器预设。RegisterShadersEvent/TextureAtlasStitchedEvent 在 1.20.1 走 game 总线，
 * 由 {@link ShaderEventRegistrar} 处理（见该类的总线说明）。</p>
 */
@Mod.EventBusSubscriber(modid = tizMod.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class tizModClient {

    private tizModClient() {}

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        tizMod.LOGGER.info("YizMod QZK 1.20.1 前置库客户端初始化");

        // 属性编辑台 Screen 注册（GUI 打开）
        event.enqueueWork(() ->
            net.minecraft.client.gui.screens.MenuScreens.register(
                net.minecraft.client.yiz.editor.AttributeEditorRegistries.ATTRIBUTE_EDITOR_MENU.get(),
                net.minecraft.client.yiz.editor.AttributeEditorScreen::new));

        // 注册着色器预设 (1=星芒, 2=图标贴图, 3=曲速穿越)
        ShaderManager.registerPreset("1", new ShaderManager.ShaderDescriptor(
                tizMod.MODID, "rendertype_cosmic2", "rendertype_cosmic2_armor", true
        ));
        ShaderManager.registerPreset("2", new ShaderManager.ShaderDescriptor(
                tizMod.MODID, "rendertype_cosmic3", "rendertype_cosmic3_armor", true
        ));
        ShaderManager.registerPreset("3", new ShaderManager.ShaderDescriptor(
                tizMod.MODID, "rendertype_cosmic4", "rendertype_cosmic4_armor", true
        ));
        // z系列：70%透明黑底 + 星光铠甲（能看到皮肤）
        ShaderManager.registerPreset("z1", new ShaderManager.ShaderDescriptor(
                tizMod.MODID, "rendertype_cosmic2", "rendertype_cosmic2_armor_z", true
        ));
        ShaderManager.registerPreset("z2", new ShaderManager.ShaderDescriptor(
                tizMod.MODID, "rendertype_cosmic3", "rendertype_cosmic3_armor_z", true
        ));
        ShaderManager.registerPreset("z3", new ShaderManager.ShaderDescriptor(
                tizMod.MODID, "rendertype_cosmic4", "rendertype_cosmic4_armor_z", true
        ));
        // z0: 纯取消盔甲渲染（调试用）
        ShaderManager.registerPreset("z0", new ShaderManager.ShaderDescriptor(
                tizMod.MODID, "rendertype_cosmic2", "rendertype_cosmic2_armor", true
        ));
    }
}
