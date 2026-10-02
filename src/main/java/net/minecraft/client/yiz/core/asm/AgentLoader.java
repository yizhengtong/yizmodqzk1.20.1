package net.minecraft.client.yiz.core.asm;

import net.minecraft.client.yiz.tizMod;
import org.slf4j.Logger;

/**
 * Agent 加载器门面。
 * 在 FMLCommonSetupEvent 阶段调用，使用 HotSpotAttachLoader 同进程 self-attach 加载 agent。
 *
 * <p><b>备注</b>：开发环境（{@code gradlew runClient}）self-attach 必然降级为反射扫描模式，
 * 属预期行为（原因见 {@link AbstractAgentLoader} 类注释）；生产环境走清单的
 * {@code Launcher-Agent-Class}/{@code Premain-Class} 不降级。</p>
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
            // 开发环境会走到这里（预期），见 AbstractAgentLoader 类注释
            LOGGER.error("Agent 加载失败，进入降级模式: {}", e.getMessage(), e);
        }
    }

    public static boolean isLoaded() {
        return loader != null && loader.isLoaded();
    }
}
