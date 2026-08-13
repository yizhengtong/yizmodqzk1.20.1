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
            inst.addTransformer(new KeyCompareDumpTransformer(), true);
            storeInstrumentation(inst);       // agentActive = true
            markTransformerRegistered(inst);  // transformerRegistered = true
            // 注入 AgentBridge Class 到 transformer：retransform 线程 context classloader 拿不到游戏类，
            // readSuperName/isLivingEntitySubclass 需要用它解析 Mob/LivingEntity 等游戏父类
            Class<?> bridge = resolveBridge(inst);
            if (bridge != null) LivingHealthTransformer.BRIDGE_CLASS_REF = bridge;
            retransformLoadedEntities(inst);
            // retransform 已触发 transform → Class.forName(AgentBridge) 已加载 AgentBridge。
            // 上方首次 storeInstrumentation 时 AgentBridge 尚未加载（resolveBridge 枚举不到 → 静默失败），
            // 导致 AgentBridge.instrumentation 字段一直是 null → KeyHunter 枚举误走无 agent 兜底（0 类）。
            // 此处 AgentBridge 已加载，补存 Instrumentation，让 getAllLoadedClasses 全量枚举生效。
            storeInstrumentation(inst);
            System.err.println("[HealthAgent] Agent initialized successfully");
        } catch (Throwable t) {
            setLastError(inst, "agentmain 初始化失败: " + t.getMessage());
            System.err.println("[HealthAgent] FAILED to initialize agent: " + t.getMessage());
            t.printStackTrace();
            throw t;
        }
    }

    /**
     * 从已加载类里直接取 AgentBridge 的 Class（agentmain 线程的 context classloader 拿不到
     * 游戏类加载器，Class.forName 会失败；改用 Instrumentation 枚举已加载类绕过）。
     */
    private static Class<?> resolveBridge(Instrumentation inst) {
        try {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            if (cl != null) {
                try { return Class.forName(BRIDGE_CLASS, true, cl); } catch (Throwable ignored) {}
            }
            if (inst != null) {
                // ① AgentBridge 可能已加载 → 直接枚举命中
                for (Class<?> c : inst.getAllLoadedClasses()) {
                    if (BRIDGE_CLASS.equals(c.getName())) return c;
                }
                // ② AgentBridge 未加载 → 借任一已加载的本模组类（如 tizMod）的游戏 loader 强制加载它。
                //    这样 storeInstrumentation 在 retransform 之前就能成功，不依赖 retransform 是否完整
                //    （retransform 可能被第三方 ILaunchPluginService 的异常中断，导致 instrumentation 补存失败）。
                for (Class<?> c : inst.getAllLoadedClasses()) {
                    String n = c.getName();
                    if (n.startsWith("net.minecraft.client.yiz")) {
                        try {
                            ClassLoader game = c.getClassLoader();
                            if (game != null) return Class.forName(BRIDGE_CLASS, true, game);
                        } catch (Throwable ignored) {}
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** 上报 transformer 已注册（反射调主模组 AgentBridge）。 */
    private static void markTransformerRegistered(Instrumentation inst) {
        try {
            Class<?> bridge = resolveBridge(inst);
            if (bridge == null) return;
            bridge.getMethod("markTransformerRegistered").invoke(null);
        } catch (Exception e) {
            System.err.println("[HealthAgent] Failed to markTransformerRegistered: " + e.getMessage());
        }
    }

    /** 上报加载错误（静默失败检测）。 */
    private static void setLastError(Instrumentation inst, String err) {
        try {
            Class<?> bridge = resolveBridge(inst);
            if (bridge == null) return;
            bridge.getMethod("setLastError", String.class).invoke(null, err);
        } catch (Exception ignored) {}
    }

    /** 将 Instrumentation 通过反射存入主模组 AgentBridge。 */
    private static void storeInstrumentation(Instrumentation inst) {
        try {
            Class<?> bridge = resolveBridge(inst);
            if (bridge == null) return;
            bridge.getMethod("setInstrumentation", Instrumentation.class).invoke(null, inst);
        } catch (Exception e) {
            System.err.println("[HealthAgent] Failed to store Instrumentation: " + e.getMessage());
        }
    }

    /** 对已加载的游戏/模组类执行 retransform（覆盖 agent 挂载前早加载的类）。 */
    private static void retransformLoadedEntities(Instrumentation inst) {
        Class<?>[] loadedClasses = inst.getAllLoadedClasses();
        List<Class<?>> toRetransform = new ArrayList<>();
        for (Class<?> clazz : loadedClasses) {
            if (shouldRetransform(clazz)) toRetransform.add(clazz);
        }
        if (toRetransform.isEmpty()) {
            System.err.println("[HealthAgent] No loaded classes to retransform");
            return;
        }
        // 分批 retransform：整批失败时降级逐个重试，只有真正不可 retransform 的单个类才被跳过
        final int BATCH = 100;
        int done = 0;
        for (int i = 0; i < toRetransform.size(); i += BATCH) {
            int end = Math.min(i + BATCH, toRetransform.size());
            List<Class<?>> slice = toRetransform.subList(i, end);
            try {
                inst.retransformClasses(slice.toArray(new Class<?>[0]));
                done += slice.size();
            } catch (Throwable t) {
                // 逐个重试，精确定位不可 retransform 的类
                for (Class<?> c : slice) {
                    try {
                        inst.retransformClasses(c);
                        done++;
                    } catch (Throwable t2) {
                        // 真正不可 retransform（数组/record 等），跳过
                    }
                }
            }
        }
        System.err.println("[HealthAgent] Retransformation complete: " + done + "/" + toRetransform.size() + " classes");
    }

    /**
     * 是否重 transform：覆盖所有「游戏 + 模组」类（getHealth 的调用方不限于 LivingEntity 子类，
     * 如 Boss 血条渲染器 CustomBossBarEventHandler 是普通客户端类），排除 JDK/库/本模组/玩家/mixin/数组。
     */
    private static boolean shouldRetransform(Class<?> clazz) {
        if (clazz.isArray()) return false; // 数组类不可 retransform
        String name = clazz.getName();
        // 本模组实体类放行；其余本模组类排除
        if ("net.minecraft.client.yiz.xian.entity.QuanshouzheEntity".equals(name)) return true;
        if (name.startsWith("net.minecraft.client.yiz")) return false;
        if (name.startsWith("net.minecraft.client.player")) return false; // 客户端玩家由 Mixin 处理
        if (name.contains("$$")) return false; // Mixin 生成类
        // JDK
        if (name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("jdk.")
                || name.startsWith("sun.") || name.startsWith("com.sun.")) return false;
        // 库（重 transform 无意义且可能触发模块类加载）
        if (name.startsWith("org.objectweb.asm") || name.startsWith("cpw.mods")
                || name.startsWith("org.slf4j") || name.startsWith("io.netty")
                || name.startsWith("it.unimi.dsi") || name.startsWith("org.apache")
                || name.startsWith("org.lwjgl") || name.startsWith("com.google")
                || name.startsWith("org.spongepowered") || name.startsWith("org.jetbrains")
                || name.startsWith("org.joml") || name.startsWith("com.electronwill")) return false;
        return true;
    }

    public static void premain(String args, Instrumentation inst) {
        agentmain(args, inst);
    }
}
