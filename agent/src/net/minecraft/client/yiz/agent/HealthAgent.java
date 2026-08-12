package net.minecraft.client.yiz.agent;

import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.util.ArrayList;
import java.util.List;

/**
 * Java Agent 入口点（1.20.1 移植版）。
 * 由 VirtualMachine.loadAgent() 调用 agentmain()，注册 LivingHealthTransformer。
 */
public final class HealthAgent {

    private HealthAgent() {}

    private static final String BRIDGE_CLASS = "net.minecraft.client.yiz.core.asm.AgentBridge";

    public static void agentmain(String args, Instrumentation inst) {
        System.err.println("[HealthAgent] Initializing agent...");
        try {
            inst.addTransformer(new LivingHealthTransformer(), true);
            storeInstrumentation(inst);       // agentActive = true
            markTransformerRegistered();       // transformerRegistered = true
            retransformLoadedEntities(inst);
            System.err.println("[HealthAgent] Agent initialized successfully");
        } catch (Throwable t) {
            setLastError("agentmain 初始化失败: " + t.getMessage());
            System.err.println("[HealthAgent] FAILED to initialize agent: " + t.getMessage());
            t.printStackTrace();
            throw t;
        }
    }

    /** 上报 transformer 已注册（反射调主模组 AgentBridge）。 */
    private static void markTransformerRegistered() {
        try {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            if (cl == null) cl = HealthAgent.class.getClassLoader();
            Class<?> bridge = Class.forName(BRIDGE_CLASS, true, cl);
            bridge.getMethod("markTransformerRegistered").invoke(null);
        } catch (Exception e) {
            System.err.println("[HealthAgent] Failed to markTransformerRegistered: " + e.getMessage());
        }
    }

    /** 上报加载错误（静默失败检测）。 */
    private static void setLastError(String err) {
        try {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            if (cl == null) cl = HealthAgent.class.getClassLoader();
            Class<?> bridge = Class.forName(BRIDGE_CLASS, true, cl);
            bridge.getMethod("setLastError", String.class).invoke(null, err);
        } catch (Exception ignored) {}
    }

    /** 将 Instrumentation 通过反射存入主模组 AgentBridge。用 context classloader 加载（agent 隔离 classloader 访问不到 mod 类）。 */
    private static void storeInstrumentation(Instrumentation inst) {
        try {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            if (cl == null) cl = HealthAgent.class.getClassLoader();
            Class<?> bridge = Class.forName(BRIDGE_CLASS, true, cl);
            bridge.getMethod("setInstrumentation", Instrumentation.class).invoke(null, inst);
        } catch (Exception e) {
            System.err.println("[HealthAgent] Failed to store Instrumentation: " + e.getMessage());
        }
    }

    /** 对已加载的 LivingEntity 子类执行 retransform。 */
    private static void retransformLoadedEntities(Instrumentation inst) {
        Class<?>[] loadedClasses = inst.getAllLoadedClasses();
        List<Class<?>> toRetransform = new ArrayList<>();
        for (Class<?> clazz : loadedClasses) {
            if (isModifiableEntityClass(clazz)) toRetransform.add(clazz);
        }
        if (toRetransform.isEmpty()) {
            System.err.println("[HealthAgent] No loaded LivingEntity subclasses to retransform");
            return;
        }
        try {
            inst.retransformClasses(toRetransform.toArray(new Class<?>[0]));
            System.err.println("[HealthAgent] Retransformation complete: " + toRetransform.size() + " classes");
        } catch (UnmodifiableClassException e) {
            System.err.println("[HealthAgent] Some classes could not be retransformed: " + e.getMessage());
        } catch (Throwable t) {
            System.err.println("[HealthAgent] Retransformation failed: " + t.getMessage());
        }
    }

    private static boolean isModifiableEntityClass(Class<?> clazz) {
        String name = clazz.getName();
        // 本模组实体类：辖界者 + 实体基类 YizxianMob（getHealth 定义在基类，必须 agent 外层包装
        // 覆盖外部 agent 注入 → 免改；其余 yiz 工具类排除防循环）
        if ("net.minecraft.client.yiz.xian.entity.QuanshouzheEntity".equals(name)
                || "net.minecraft.client.yiz.xian.entity.base.YizxianMob".equals(name)) {
            return isLivingEntitySubclass(clazz);
        }
        if (name.startsWith("net.minecraft.client.yiz")) return false;
        if (name.startsWith("net.minecraft.client.player")) return false;
        if (name.contains("$$")) return false;
        return isLivingEntitySubclass(clazz);
    }

    private static boolean isLivingEntitySubclass(Class<?> clazz) {
        Class<?> superClass = clazz.getSuperclass();
        while (superClass != null) {
            if (superClass.getName().equals("net.minecraft.world.entity.LivingEntity")) {
                return true;
            }
            superClass = superClass.getSuperclass();
        }
        return false;
    }

    public static void premain(String args, Instrumentation inst) {
        agentmain(args, inst);
    }
}
