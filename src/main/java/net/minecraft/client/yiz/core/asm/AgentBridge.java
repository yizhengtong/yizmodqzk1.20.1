package net.minecraft.client.yiz.core.asm;

import net.minecraft.client.yiz.tizMod;
import org.slf4j.Logger;

import java.lang.instrument.Instrumentation;

/**
 * Agent 桥接器（1.20.1 移植版）。
 * 连接 Java Agent 与主模组，提供 Instrumentation 访问。
 * 由 HealthAgent 通过反射注入 Instrumentation 实例（agent jar 编译时不依赖主源码）。
 */
public final class AgentBridge {

    private static final Logger LOGGER = tizMod.LOGGER;

    private static volatile Instrumentation instrumentation;
    private static volatile boolean agentActive = false;
    private static volatile boolean agentTransformed = false;

    private AgentBridge() {}

    /** 由 HealthAgent 在 agentmain 时通过反射调用。 */
    @SuppressWarnings("unused")
    public static void setInstrumentation(Instrumentation inst) {
        instrumentation = inst;
        agentActive = true;
        LOGGER.info("[AgentBridge] Instrumentation received");
    }

    /** 由 LivingHealthTransformer 通过反射调用，标记已转换过类。 */
    @SuppressWarnings("unused")
    public static void markTransformed() {
        agentTransformed = true;
    }

    public static boolean isAgentActive() { return agentActive; }
    public static boolean isAgentTransformed() { return agentTransformed; }
    public static Instrumentation getInstrumentation() { return instrumentation; }
}
