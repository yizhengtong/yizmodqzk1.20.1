package net.minecraft.client.yiz.core.asm;

import net.minecraft.client.yiz.tizMod;
import org.slf4j.Logger;

/**
 * Agent 加载器门面（1.20.1 移植版）。
 * 在 FMLCommonSetupEvent 阶段调用，使用 HotSpotAttachLoader 同进程 self-attach 加载 agent。
 */
public final class AgentLoader {

    private static final Logger LOGGER = tizMod.LOGGER;
    private static volatile IAgentLoader loader;

    private AgentLoader() {}

    /** 初始化并加载 Agent（FMLCommonSetup 或更早阶段调用）。 */
    public static void init() {
        try {
            loader = new HotSpotAttachLoader();
            loader.loadAgent();
        } catch (RuntimeException e) {
            LOGGER.error("Agent 加载失败，进入降级模式: {}", e.getMessage(), e);
        }
    }

    public static boolean isLoaded() {
        return loader != null && loader.isLoaded();
    }
}
