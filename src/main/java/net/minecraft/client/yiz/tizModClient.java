package net.minecraft.client.yiz;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/**
 * 前置库客户端入口（1.20.1）。
 *
 * <p>客户端初始化期注册：属性编辑台 Screen、万能物品配置按键。
 * 着色器注册在 {@link ShaderEventRegistrar}（MOD 总线），渲染期事件在
 * {@link ShaderRenderRegistrar}（game 总线）。</p>
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

        // 万能物品配置：Shift+U 按键响应
        net.minecraft.client.yiz.itemcfg.client.ItemConfigKeyHandler.init();
    }

    /** 注册万能物品配置按键（U 键，Shift+U 触发）。 */
    @SubscribeEvent
    public static void onRegisterKeyMappings(
            net.minecraftforge.client.event.RegisterKeyMappingsEvent event) {
        tizMod.LOGGER.info("ItemConfig RegisterKeyMappingsEvent 触发");
        net.minecraft.client.yiz.itemcfg.client.ItemConfigKeyHandler.registerKeyMapping(event);
    }
}
