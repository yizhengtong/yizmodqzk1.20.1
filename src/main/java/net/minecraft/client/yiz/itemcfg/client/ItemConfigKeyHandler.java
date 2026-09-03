package net.minecraft.client.yiz.itemcfg.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shift+U 按键：手持物品时打开万能物品配置 GUI。
 *
 * <p>检测用 GLFW 物理按键直读 + 边沿检测（不依赖 KeyMapping 注册状态）——
 * vanilla KeyboardHandler 只遍历 options.keyMappings 更新 KeyMapping 状态，
 * 若 RegisterKeyMappingsEvent 未注册成功则 consumeClick 永远 false。
 * KeyMapping 仍注册（供设置页显示/改绑），但触发逻辑走物理键。</p>
 */
public final class ItemConfigKeyHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger("ItemConfigKey");

    public static final String CATEGORY = "yizmodqzk";
    public static final KeyMapping OPEN_CONFIG = new KeyMapping(
            "key.yizmodqzk.itemcfg", InputConstants.KEY_U, CATEGORY);

    /** U 键上一 tick 状态（边沿检测，防按住重复触发）。 */
    private static boolean prevUDown = false;

    private ItemConfigKeyHandler() {}

    /** 客户端事件注册（tizModClient.onClientSetup 调用）。 */
    public static void init() {
        MinecraftForge.EVENT_BUS.register(ItemConfigKeyHandler.class);
    }

    /** 注册按键到设置（RegisterKeyMappingsEvent）。 */
    public static void registerKeyMapping(RegisterKeyMappingsEvent event) {
        event.register(OPEN_CONFIG);
        LOGGER.info("ItemConfig 按键 U 已注册到选项");
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.screen != null) {
            prevUDown = false;
            return;
        }
        long win = mc.getWindow().getWindow();
        boolean uDown = InputConstants.isKeyDown(win, InputConstants.KEY_U);
        boolean shiftDown = Screen.hasShiftDown();
        if (uDown && !prevUDown && shiftDown) {
            LOGGER.info("ItemConfig Shift+U 触发");
            ItemStack held = mc.player.getMainHandItem();
            if (held.isEmpty()) {
                LOGGER.info("ItemConfig 主手空，不打开");
                prevUDown = uDown;
                return;
            }
            var id = ForgeRegistries.ITEMS.getKey(held.getItem());
            if (id != null) ItemConfigClientHandler.request(id.toString());
        }
        prevUDown = uDown;
    }
}
