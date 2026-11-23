package net.minecraft.client.yiz.anim.editor;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.yiz.tizMod;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

/** K 键：打开 UE 风格动画编辑器（按键检测在 {@code AnimClientEvents.onClientTick}）。 */
@Mod.EventBusSubscriber(modid = tizMod.MODID,
        bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class AnimEditorKey {

    public static final KeyMapping OPEN = new KeyMapping(
            "key.yizmodqzk.anim_editor", GLFW.GLFW_KEY_K, "key.categories.yizmodqzk");

    private AnimEditorKey() {}

    @SubscribeEvent
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(OPEN);
    }
}
