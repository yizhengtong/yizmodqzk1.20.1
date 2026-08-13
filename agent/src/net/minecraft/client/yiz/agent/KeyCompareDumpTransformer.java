package net.minecraft.client.yiz.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.reflect.Method;
import java.security.ProtectionDomain;

/**
 * 通用「内部 Key 判定」字节码攻取 transformer（KeyHunter 字节码层）。
 *
 * <p>只对 {@code /yiz key watch} 开启的前缀范围生效（每次 transform 反射问主模组
 * {@code KeyDumpBridge.isWatching}，与 {@link LivingHealthTransformer} 同一桥接模式）。
 * 三处注入全部<b>按类型特征匹配，不依赖目标任何名字</b>：</p>
 * <ul>
 *   <li>{@code StackWalker.walk (Function)Object} 调用点 → {@code KeyDumpBridge.nullWalk}
 *       （空帧流 → 目标「存在坏帧」判定恒 false）；</li>
 *   <li>{@code StackWalker.getCallerClass ()Class} 调用点 → {@code POP + LDC 本类名 +
 *       KeyDumpBridge.trustedCaller}（调用者恒等于闸门自己的类，必在白名单内）；</li>
 *   <li>密钥比较指令（{@code Arrays.compare/equals/mismatch([B[B)}、
 *       {@code MessageDigest.isEqual([B[B)}、{@code String.equals(String)}）前插入
 *       {@code DUP2 + KeyDumpBridge.logCompare}：比较发生的那一刻转储两个操作数——
 *       无论密钥如何计算/混淆，期望密钥必在操作数栈上。</li>
 * </ul>
 */
public class KeyCompareDumpTransformer implements ClassFileTransformer {

    private static final String BRIDGE_CLASS = "net.minecraft.client.yiz.tool.key.KeyDumpBridge";
    private static volatile Method isWatchingMethod;
    private static volatile Method isCaptureEnabledMethod;
    private static volatile boolean bridgeLookupFailed = false;

    @Override
    public byte[] transform(
            ClassLoader loader,
            String className,
            Class<?> classBeingRedefined,
            ProtectionDomain protectionDomain,
            byte[] classfileBuffer
    ) throws IllegalClassFormatException {
        if (className == null || classfileBuffer == null) return null;
        if (!isWatching(className.replace('/', '.'))) return null;

        try {
            ClassReader cr = new ClassReader(classfileBuffer);
            ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_FRAMES);
            boolean[] modified = {false};
            cr.accept(new KeyWatchClassVisitor(cw, className, modified), ClassReader.EXPAND_FRAMES);
            return modified[0] ? cw.toByteArray() : null;
        } catch (Throwable t) {
            System.err.println("[KeyWatch] transform failed for " + className.replace('/', '.')
                    + ": " + t.getClass().getName() + " - " + t.getMessage());
            return null;
        }
    }

    /** 反射问主模组 KeyDumpBridge（agent 隔离 classloader 访问不到 mod 类）；watch 关闭时快速短路。 */
    private static boolean isWatching(String dotted) {
        try {
            ensureBridgeMethods();
            if (isCaptureEnabledMethod != null
                    && !Boolean.TRUE.equals(isCaptureEnabledMethod.invoke(null))) {
                return false; // 未 watch → 零注入开销
            }
            if (isWatchingMethod == null) return false;
            return Boolean.TRUE.equals(isWatchingMethod.invoke(null, dotted));
        } catch (Throwable t) {
            return false;
        }
    }

    private static synchronized void ensureBridgeMethods() {
        if (bridgeLookupFailed) return;
        if (isWatchingMethod != null && isCaptureEnabledMethod != null) return;
        try {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            if (cl == null) cl = KeyCompareDumpTransformer.class.getClassLoader();
            Class<?> bridge = Class.forName(BRIDGE_CLASS, true, cl);
            isWatchingMethod = bridge.getMethod("isWatching", String.class);
            isCaptureEnabledMethod = bridge.getMethod("isCaptureEnabled");
        } catch (Throwable t) {
            bridgeLookupFailed = true;
        }
    }

    private static class KeyWatchClassVisitor extends ClassVisitor {

        private final String className;
        private final boolean[] modified;

        KeyWatchClassVisitor(ClassVisitor cv, String className, boolean[] modified) {
            super(Opcodes.ASM9, cv);
            this.className = className;
            this.modified = modified;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                         String signature, String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
            if (mv == null) return null;
            return new KeyWatchMethodVisitor(mv, className, modified);
        }
    }

    private static class KeyWatchMethodVisitor extends MethodVisitor {

        private final String className;
        private final boolean[] modified;

        KeyWatchMethodVisitor(MethodVisitor mv, String className, boolean[] modified) {
            super(Opcodes.ASM9, mv);
            this.className = className;
            this.modified = modified;
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
            // ① StackWalker.walk → nullWalk（空帧流，闸门恒通过）
            //    原形: [..., walker, function] INVOKEVIRTUAL → 静态改写必须先 POP walker，否则栈残留
            if (opcode == Opcodes.INVOKEVIRTUAL
                    && "java/lang/StackWalker".equals(owner)
                    && "walk".equals(name)
                    && "(Ljava/util/function/Function;)Ljava/lang/Object;".equals(descriptor)) {
                super.visitInsn(Opcodes.POP);                              // 丢弃 StackWalker 实例
                super.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE_CLASS, "nullWalk", descriptor, false);
                modified[0] = true;
                return;
            }
            // ② StackWalker.getCallerClass → trustedCaller(本类名)：调用者恒为闸门自己的类
            if (opcode == Opcodes.INVOKEVIRTUAL
                    && "java/lang/StackWalker".equals(owner)
                    && "getCallerClass".equals(name)
                    && "()Ljava/lang/Class;".equals(descriptor)) {
                super.visitInsn(Opcodes.POP);                              // 丢弃 StackWalker 实例
                super.visitLdcInsn(className.replace('/', '.'));
                super.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE_CLASS, "trustedCaller",
                        "(Ljava/lang/String;)Ljava/lang/Class;", false);
                modified[0] = true;
                return;
            }
            // ③ 密钥比较点 → 比较前转储两个操作数（密钥活在栈上）
            if (isKeyCompareSite(opcode, owner, name, descriptor)) {
                super.visitInsn(Opcodes.DUP2);                             // [..., a, b, a, b]
                super.visitLdcInsn(className.replace('/', '.') + "." + name + descriptor);
                super.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE_CLASS, "logCompare",
                        "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;)V", false);
                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface); // [..., a, b] 原比较
                modified[0] = true;
                return;
            }
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
        }

        /** 密钥比较家族：只收双引用参数形态（DUP2 才能保持栈形），避免误伤其它比较。 */
        private static boolean isKeyCompareSite(int opcode, String owner, String name, String descriptor) {
            if (opcode == Opcodes.INVOKESTATIC && "java/util/Arrays".equals(owner)) {
                return ("compare".equals(name) && "([B[B)I".equals(descriptor))
                        || ("equals".equals(name) && "([B[B)Z".equals(descriptor))
                        || ("mismatch".equals(name) && "([B[B)I".equals(descriptor));
            }
            if (opcode == Opcodes.INVOKESTATIC && "java/security/MessageDigest".equals(owner)) {
                return "isEqual".equals(name) && "([B[B)Z".equals(descriptor);
            }
            if (opcode == Opcodes.INVOKEVIRTUAL && "java/lang/String".equals(owner)) {
                return "equals".equals(name) && "(Ljava/lang/String;)Z".equals(descriptor);
            }
            return false;
        }
    }
}
