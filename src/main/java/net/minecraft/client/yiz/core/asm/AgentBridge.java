package net.minecraft.client.yiz.core.asm;

import net.minecraft.client.yiz.tizMod;
import net.minecraft.client.yiz.tool.key.KeyDumpBridge;
import org.slf4j.Logger;

import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
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

    // ==================== Key Watch（KeyCompareDumpTransformer 控制）====================

    /** 本次 watch 已 retransform 的类（unwatch 时用同一列表还原为原始字节码）。 */
    private static final List<Class<?>> watchedClasses = new ArrayList<>();

    /**
     * 开启 key watch：设置 {@link KeyDumpBridge} 前缀 → 对已加载的目标类 retransform
     * （agent 端 KeyCompareDumpTransformer 对匹配类注入 StackWalker 改写 + 比较点密钥转储）。
     * 返回 retransform 的类数；-1 = agent 不可用（transformer 未注册时新加载类也不会被注入）。
     */
    public static int enableKeyWatch(Collection<String> prefixes) {
        List<String> cleaned = new ArrayList<>();
        if (prefixes != null) {
            for (String p : prefixes) {
                if (p == null || p.isBlank()) continue;
                String t = p.trim();
                // 防自注入：本家族/游戏/平台前缀一律拒绝
                if (t.startsWith("net.minecraft.client.yiz")
                        || t.startsWith("net.minecraft.")
                        || t.startsWith("java.")
                        || t.startsWith("jdk.")
                        || t.startsWith("sun.")
                        || t.startsWith("com.mojang.")
                        || t.startsWith("org.spongepowered.")) {
                    continue;
                }
                cleaned.add(t);
            }
        }
        KeyDumpBridge.setWatchPrefixes(cleaned);
        if (cleaned.isEmpty()) return 0;
        if (instrumentation == null) return -1;

        List<Class<?>> targets = new ArrayList<>();
        for (Class<?> c : instrumentation.getAllLoadedClasses()) {
            if (KeyDumpBridge.isWatching(c.getName())) targets.add(c);
        }
        synchronized (watchedClasses) {
            watchedClasses.clear();
            watchedClasses.addAll(targets);
        }
        if (targets.isEmpty()) return 0;
        int done = retransformLenient(targets);
        LOGGER.info("[AgentBridge] key watch retransform 完成: {}/{} 个类", done, targets.size());
        return done;
    }

    /** 关闭 key watch：清空前缀 → 对上次 watch 的类 retransform（transformer 返回 null → 还原原始字节码）。 */
    public static int disableKeyWatch() {
        List<Class<?>> targets;
        synchronized (watchedClasses) {
            targets = new ArrayList<>(watchedClasses);
            watchedClasses.clear();
        }
        KeyDumpBridge.setWatchPrefixes(Collections.emptySet());
        KeyDumpBridge.clearCaptured();
        if (instrumentation == null || targets.isEmpty()) return 0;
        return retransformLenient(targets);
    }

    /** 分批 retransform + 逐个降级：跳过不可 retransform 的类（record/lambda/数组等），返回成功数。 */
    private static int retransformLenient(List<Class<?>> targets) {
        final int BATCH = 100;
        int done = 0;
        for (int i = 0; i < targets.size(); i += BATCH) {
            int end = Math.min(i + BATCH, targets.size());
            List<Class<?>> slice = targets.subList(i, end);
            try {
                instrumentation.retransformClasses(slice.toArray(new Class<?>[0]));
                done += slice.size();
            } catch (Throwable t) {
                for (Class<?> c : slice) {
                    try {
                        instrumentation.retransformClasses(c);
                        done++;
                    } catch (Throwable t2) {
                        // 真正不可 retransform 的类，跳过
                    }
                }
            }
        }
        return done;
    }

    public static List<String> getKeyWatchPrefixes() {
        return KeyDumpBridge.getWatchPrefixes();
    }
}
