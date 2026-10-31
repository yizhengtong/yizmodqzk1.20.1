package net.minecraft.client.yiz.tool.defense;

import net.minecraft.client.yiz.core.asm.AgentBridge;
import net.minecraft.client.yiz.tool.safety.CrashGuard;
import net.minecraft.client.yiz.tool.safety.TickSafeQueue;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.instrument.Instrumentation;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * P2b：字节码常量改写（实验性，默认关闭：{@code pohuan.bytecode_patch}）。
 *
 * <p>依据 {@link DefenseScanner} 画像，在 retransform 时把
 * {@code hurt}/{@code actuallyHurt}/{@code setHealth} 中、被 {@code Math.min/max}
 * 使用的疑似上限常量替换为同类型的 MAX。只替换同类型常量，不改变栈类型/帧。</p>
 *
 * <p>只在 {@code PohuanXunzhen} 命中且画像有 cap 候选时，按类延迟触发一次；
 * 失败自动跳过，不影响运行时字段破除（P2a）。</p>
 */
public final class DefensePatcher implements java.lang.instrument.ClassFileTransformer {

    private static volatile DefensePatcher INSTANCE;
    private static final Set<String> PATCHED = ConcurrentHashMap.newKeySet();

    private DefensePatcher() {}

    /** 安装 transformer（幂等）。需要 agent 已附加。 */
    public static void install() {
        if (INSTANCE != null) return;
        synchronized (DefensePatcher.class) {
            if (INSTANCE != null) return;
            try {
                Instrumentation inst = AgentBridge.getInstrumentation();
                if (inst == null) return;
                DefensePatcher t = new DefensePatcher();
                inst.addTransformer(t, true);
                INSTANCE = t;
            } catch (Throwable t) {
                CrashGuard.report("pohuan-patch-install", t);
            }
        }
    }

    /** 按类请求改写（tick 后置执行，避免在 hurt 栈内 retransform）。 */
    public static void requestPatch(Class<?> cls) {
        if (cls == null) return;
        String name = cls.getName();
        if (PATCHED.contains(name)) return;
        TickSafeQueue.enqueue("pohuan-patch", () -> patchNow(cls));
    }

    private static void patchNow(Class<?> cls) {
        if (cls == null) return;
        DefenseProfile p = DefenseProfile.cached(cls.getName());
        if (p == null || p.caps.isEmpty()) return;
        try {
            install();
            Instrumentation inst = AgentBridge.getInstrumentation();
            if (inst == null) return;
            // 上限常量可能写在父类（抽象基类）里：按 cap.owner 逐类改写
            java.util.Set<String> owners = new java.util.LinkedHashSet<>();
            for (DefenseProfile.CapCandidate c : p.caps) {
                owners.add(c.owner != null ? c.owner : cls.getName());
            }
            int patched = 0;
            for (String owner : owners) {
                if (PATCHED.contains(owner)) continue;
                Class<?> target = owner.equals(cls.getName()) ? cls
                    : Class.forName(owner, false, cls.getClassLoader());
                inst.retransformClasses(target);
                PATCHED.add(owner);
                patched++;
            }
            if (patched > 0) {
                final int n = patched;
                CrashGuard.run("pohuan-patch-log", () ->
                    net.minecraft.client.yiz.tizMod.LOGGER.info("[破幻寻真] 字节码上限常量已改写 {} 个类（入口 {}）",
                        n, cls.getName()));
            }
        } catch (VirtualMachineError | ThreadDeath fatal) {
            throw fatal;
        } catch (Throwable t) {
            CrashGuard.report("pohuan-patch", t);
        }
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            java.security.ProtectionDomain pd, byte[] bytes) {
        if (className == null || bytes == null) return null;
        try {
            DefenseProfile p = DefenseProfile.cached(className.replace('/', '.'));
            if (p == null || p.caps.isEmpty()) return null;
            ClassReader cr = new ClassReader(bytes);
            ClassWriter cw = new ClassWriter(cr, 0);
            ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, cw) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String desc,
                                                 String sig, String[] ex) {
                    MethodVisitor mv = super.visitMethod(access, name, desc, sig, ex);
                    if (mv == null) return null;
                    Map<Double, String> caps = new java.util.HashMap<>();
                    for (DefenseProfile.CapCandidate c : p.caps) {
                        if (name.equals(c.method)) caps.put(c.value, c.type);
                    }
                    if (caps.isEmpty()) return mv;
                    return new MethodVisitor(Opcodes.ASM9, mv) {
                        @Override
                        public void visitLdcInsn(Object value) {
                            if (value instanceof Number n) {
                                String type = caps.get(n.doubleValue());
                                if (type != null && typeMatches(type, n)) {
                                    Object max = maxFor(n);
                                    if (max != null) {
                                        super.visitLdcInsn(max);
                                        return;
                                    }
                                }
                            }
                            super.visitLdcInsn(value);
                        }
                    };
                }
            };
            cr.accept(cv, 0);
            return cw.toByteArray();
        } catch (VirtualMachineError | ThreadDeath fatal) {
            throw fatal;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean typeMatches(String type, Number n) {
        return switch (type) {
            case "float" -> n instanceof Float;
            case "double" -> n instanceof Double;
            case "int" -> n instanceof Integer;
            case "long" -> n instanceof Long;
            default -> false;
        };
    }

    private static Object maxFor(Number n) {
        if (n instanceof Float) return Float.MAX_VALUE;
        if (n instanceof Double) return Double.MAX_VALUE;
        if (n instanceof Integer) return Integer.MAX_VALUE;
        if (n instanceof Long) return Long.MAX_VALUE;
        return null;
    }
}
