package net.minecraft.client.yiz.tool;

import java.util.Set;

/**
 * 外部调用判定工具 — 全栈区段白名单检查。1.20.1 移植版（引擎前缀改 net.minecraftforge）。
 */
public final class ExternalCallGuard {

    private static final String FAMILY_PACKAGE = "net.minecraft.client.yiz";
    private static final String[] ENGINE_PREFIXES = {"net.minecraft.", "net.minecraftforge.", "com.mojang."};

    private ExternalCallGuard() {}

    /**
     * 当前调用栈是否全部来自受信任帧。
     *
     * @param skipMethods 需跳过的门禁方法名（override 自身链），可为 null
     */
    public static boolean isTrustedCall(Set<String> skipMethods) {
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        for (int i = 3; i < stack.length; i++) {
            String cn = stack[i].getClassName();
            if (cn.startsWith(FAMILY_PACKAGE)) {
                if (skipMethods != null && skipMethods.contains(stack[i].getMethodName())) continue;
                continue;
            }
            if (isEngineFrame(cn, stack[i].getMethodName())) continue;
            return false;
        }
        return true;
    }

    private static boolean isEngineFrame(String className, String methodName) {
        if (className.startsWith("net.minecraft.world.level.Explosion")) return false;
        for (String p : ENGINE_PREFIXES) {
            if (className.startsWith(p)) {
                return !isExternalMixinFrame(className, methodName);
            }
        }
        return false;
    }

    private static boolean isExternalMixinFrame(String className, String methodName) {
        try {
            for (java.lang.reflect.Method m : Class.forName(className).getDeclaredMethods()) {
                if (!m.getName().equals(methodName)) continue;
                var anno = m.getAnnotation(org.spongepowered.asm.mixin.transformer.meta.MixinMerged.class);
                if (anno != null) {
                    return !anno.mixin().startsWith(FAMILY_PACKAGE);
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }
}
