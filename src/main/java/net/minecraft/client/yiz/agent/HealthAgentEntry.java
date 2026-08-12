package net.minecraft.client.yiz.agent;

import java.lang.instrument.Instrumentation;
import java.util.function.Consumer;

/**
 * Agent 入口（bootstrap 兼容，只引用 JDK 类）。
 *
 * <p>由空 agent jar 的 {@code Launcher-Agent-Class} 指定，经 {@code Unsafe.defineClass0}
 * 定义到 bootstrap loader 后被 JVM 调用。只引用 JDK（Consumer/System/Instrumentation），
 * 把 Instrumentation 通过 {@code System.getProperties} 的 Consumer 回调传回主模组，
 * 由主模组注册 transformer（避免 bootstrap loader 解析不到 mod 类）。</p>
 */
public final class HealthAgentEntry {

    private HealthAgentEntry() {}

    public static void agentmain(String args, Instrumentation inst) {
        Object o = System.getProperties().get("yizmodqzk.loadAgent");
        if (o instanceof Consumer) {
            ((Consumer<Instrumentation>) o).accept(inst);
        }
    }

    public static void premain(String args, Instrumentation inst) {
        agentmain(args, inst);
    }
}
