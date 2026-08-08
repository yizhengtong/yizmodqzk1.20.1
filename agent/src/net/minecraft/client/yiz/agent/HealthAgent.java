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
            storeInstrumentation(inst);
            retransformLoadedEntities(inst);
            System.err.println("[HealthAgent] Agent initialized successfully");
        } catch (Throwable t) {
            System.err.println("[HealthAgent] FAILED to initialize agent: " + t.getMessage());
            t.printStackTrace();
            throw t;
        }
    }

    /** 将 Instrumentation 通过反射存入主模组 AgentBridge。 */
    private static void storeInstrumentation(Instrumentation inst) {
        try {
            Class<?> bridge = Class.forName(BRIDGE_CLASS);
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
        if (name.startsWith("net.minecraft.client.yiz")) return false;
        if (name.startsWith("net.minecraft.client.player")) return false;
        if (name.contains("$$")) return false;
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
