package net.minecraft.client.yiz.core.asm;

import net.minecraft.client.yiz.tizMod;
import org.slf4j.Logger;

import java.lang.instrument.Instrumentation;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Agent 桥接器（1.20.1 移植版）。
 * 连接 Java Agent 与主模组，提供 Instrumentation 访问 + 完整诊断状态
 * （agentmain 是否执行 / transformer 是否注册 / 实际 transform 次数 / 加载错误），
 * 供 /yiz agent 指令展示，避免"attach 成功但注入未生效"的误判。
 * 由 HealthAgent 通过反射调用（agent jar 编译时不依赖主源码）。
 */
public final class AgentBridge {

    private static final Logger LOGGER = tizMod.LOGGER;

    private static volatile Instrumentation instrumentation;
    private static volatile boolean agentActive = false;           // agentmain 执行（Instrumentation 收到）
    private static volatile boolean agentTransformed = false;      // 至少一次 transform
    private static volatile boolean transformerRegistered = false; // addTransformer 成功
    private static final AtomicInteger transformCount = new AtomicInteger();
    private static volatile String lastError = null;

    private AgentBridge() {}

    /** 由 HealthAgent 在 agentmain 时通过反射调用。 */
    @SuppressWarnings("unused")
    public static void setInstrumentation(Instrumentation inst) {
        instrumentation = inst;
        agentActive = true;
        LOGGER.info("[AgentBridge] Instrumentation received");
    }

    /** 由 HealthAgent 在 addTransformer 成功后通过反射调用。 */
    @SuppressWarnings("unused")
    public static void markTransformerRegistered() {
        transformerRegistered = true;
        LOGGER.info("[AgentBridge] transformer 已注册");
    }

    /** 由 agent 的 transformer 处理每个类后调用（实际注入类数）。
     *  有 transform 必然说明 transformer 已注册、agentmain 已执行（transform 线程能访问本类，
     *  而 agentmain 线程的 context classloader 可能拿不到主模组类导致上报失败——这里兜底补齐状态）。 */
    @SuppressWarnings("unused")
    public static void recordTransform() {
        transformCount.incrementAndGet();
        agentTransformed = true;
        transformerRegistered = true;
        agentActive = true;
    }

    /** 记录加载/注册错误（静默失败检测用）。 */
    @SuppressWarnings("unused")
    public static void setLastError(String err) {
        lastError = err;
    }

    public static boolean isAgentActive() { return agentActive; }
    public static boolean isAgentTransformed() { return agentTransformed; }
    public static boolean isTransformerRegistered() { return transformerRegistered; }
    public static int getTransformCount() { return transformCount.get(); }
    public static String getLastError() { return lastError; }
    public static Instrumentation getInstrumentation() { return instrumentation; }
}
