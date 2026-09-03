package net.minecraft.client.yiz.itemcfg.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.yiz.itemcfg.gui.ItemConfigScreen;
import net.minecraft.client.yiz.itemcfg.network.C2SItemConfigQueryPayload;
import net.minecraft.client.yiz.itemcfg.network.S2CItemConfigStatePayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 客户端配置流程协调者：
 * Shift+U 请求 → 收 S2C 打开 GUI；GUI 内 toggle 回执 → 刷新当前 Screen。
 */
public final class ItemConfigClientHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger("ItemConfigClient");

    private static String pendingItemId = null;
    private static ItemConfigScreen currentScreen = null;

    private ItemConfigClientHandler() {}

    /** 按键触发：发查询请求，标记待打开。 */
    public static void request(String itemId) {
        pendingItemId = itemId;
        LOGGER.info("ItemConfig 发请求: {}", itemId);
        C2SItemConfigQueryPayload.send(itemId);
    }

    /** 收到服务端状态（主线程）：匹配待打开 → 打开；匹配已开 → 刷新。 */
    public static void onStateReceived(S2CItemConfigStatePayload state) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        LOGGER.info("ItemConfig 收到状态 {} entries={} pending={}",
                state.itemId, state.entries.size(), pendingItemId);
        if (state.itemId.equals(pendingItemId)) {
            pendingItemId = null;
            currentScreen = new ItemConfigScreen(mc.player.getMainHandItem(), state);
            LOGGER.info("ItemConfig 打开 GUI");
            mc.setScreen(currentScreen);
        } else if (currentScreen != null && currentScreen.matches(state.itemId)) {
            currentScreen.applyState(state);
        }
    }

    /** GUI 关闭时清理引用。 */
    public static void onClosed() {
        pendingItemId = null;
        currentScreen = null;
    }
}
