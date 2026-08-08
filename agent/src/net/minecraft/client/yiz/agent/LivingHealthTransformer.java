package net.minecraft.client.yiz.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.security.ProtectionDomain;

/**
 * ASM ClassFileTransformer（1.20.1 移植版）。
 * 在类加载时改写所有 LivingEntity 子类的关键方法：
 * - getHealth()       → 注入 specialGetHealth() 调用（delta 截断）
 * - isAlive()         → 注入 specialIsAlive()
 * - isDeadOrDying()   → 注入 specialIsDeadOrDying()
 */
public class LivingHealthTransformer implements ClassFileTransformer {

    private static final String LIVING_ENTITY = "net/minecraft/world/entity/LivingEntity";
    private static final String ASM_UTIL = "net/minecraft/client/yiz/tool/health/EntityASMUtil";
    private static final String BRIDGE_CLASS = "net.minecraft.client.yiz.core.asm.AgentBridge";

    public static volatile boolean transformed = false;

    @Override
    public byte[] transform(
            ClassLoader loader,
            String className,
            Class<?> classBeingRedefined,
            ProtectionDomain protectionDomain,
            byte[] classfileBuffer
    ) throws IllegalClassFormatException {
        if (className == null || classfileBuffer == null) return null;
        if (isExcluded(className)) return null;

        boolean isEntity = LIVING_ENTITY.equals(className);
        if (!isEntity && !isLivingEntitySubclass(classfileBuffer, loader)) return null;

        transformed = true;
        try {
            Class<?> bridgeClass = Class.forName(BRIDGE_CLASS);
            bridgeClass.getMethod("markTransformed").invoke(null);
        } catch (Exception ignored) {}

        try {
            ClassReader cr = new ClassReader(classfileBuffer);
            String superName = cr.getSuperName();
            try {
                ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_FRAMES);
                ClassVisitor cv = new HealthClassVisitor(cw, className, superName, isEntity);
                cr.accept(cv, ClassReader.EXPAND_FRAMES);
                return cw.toByteArray();
            } catch (Throwable frameFail) {
                // 复杂 mod 类重算帧失败 → COMPUTE_MAXS 安全模式（值替换注入不改变栈帧结构）
                ClassWriter cw2 = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
                ClassVisitor cv2 = new HealthClassVisitor(cw2, className, superName, isEntity);
                cr.accept(cv2, ClassReader.EXPAND_FRAMES);
                return cw2.toByteArray();
            }
        } catch (Throwable e) {
            System.err.println("[YizModQZK Agent] TRANSFORM FAILED for " + className.replace('/', '.')
                + ": " + e.getClass().getName() + " - " + e.getMessage());
            return null;
        }
    }

    // ==================== 排除名单 ====================

    private boolean isExcluded(String className) {
        if (className.startsWith("net/minecraft/client/yiz")) return true; // 本模组类不注入
        if (className.startsWith("net/minecraft/client/player")) return true; // 客户端玩家由 Mixin 处理
        if (className.contains("$$")) return true; // Mixin 生成类
        return false;
    }

    private boolean isLivingEntitySubclass(byte[] classfileBuffer, ClassLoader loader) {
        try {
            ClassReader cr = new ClassReader(classfileBuffer);
            String superName = cr.getSuperName();
            while (superName != null) {
                if (LIVING_ENTITY.equals(superName)) return true;
                if (superName.equals("java/lang/Object")) return false;
                try {
                    String resource = superName + ".class";
                    java.io.InputStream in = loader.getResourceAsStream(resource);
                    if (in == null) return false;
                    byte[] bytes = in.readAllBytes();
                    in.close();
                    ClassReader parent = new ClassReader(bytes);
                    superName = parent.getSuperName();
                } catch (Exception e) {
                    return false;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    // ==================== 方法注入 ====================

    private static class HealthClassVisitor extends ClassVisitor {

        private final String className;
        private final String superName;
        private final boolean isEntity;

        public HealthClassVisitor(ClassVisitor cv, String className, String superName, boolean isEntity) {
            super(Opcodes.ASM9, cv);
            this.className = className;
            this.superName = superName;
            this.isEntity = isEntity;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                         String signature, String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
            if (mv == null) return null;

            // getHealth()F
            if ("getHealth".equals(name) && "()F".equals(descriptor)) {
                return new GetHealthAdapter(mv);
            }
            // isAlive()Z
            if ("isAlive".equals(name) && "()Z".equals(descriptor)) {
                return new IsAliveAdapter(mv);
            }
            // isDeadOrDying()Z
            if ("isDeadOrDying".equals(name) && "()Z".equals(descriptor)) {
                return new IsDeadOrDyingAdapter(mv);
            }
            return mv;
        }
    }

    /**
     * getHealth 注入：方法开头 ALOAD 0 → INVOKESTATIC specialGetHealth(F,LivingEntity;)F → FRETURN。
     * 值替换（保留原始帧），安全。
     */
    private static class GetHealthAdapter extends MethodVisitor {

        public GetHealthAdapter(MethodVisitor mv) {
            super(Opcodes.ASM9, mv);
        }

        @Override
        public void visitCode() {
            super.visitCode();
            super.visitVarInsn(Opcodes.ALOAD, 0);          // this
            super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/minecraft/world/entity/LivingEntity",
                "getHealth", "()F", false);                 // 原始 getHealth（栈顶 float）
            super.visitVarInsn(Opcodes.ALOAD, 0);          // this
            super.visitMethodInsn(Opcodes.INVOKESTATIC, ASM_UTIL,
                "specialGetHealth", "(FLjava/lang/Object;)F", false);
            super.visitInsn(Opcodes.FRETURN);
        }
    }

    /** isAlive 注入：this → specialIsAlive(Z,LivingEntity;)Z → IRETURN。 */
    private static class IsAliveAdapter extends MethodVisitor {

        public IsAliveAdapter(MethodVisitor mv) {
            super(Opcodes.ASM9, mv);
        }

        @Override
        public void visitCode() {
            super.visitCode();
            super.visitVarInsn(Opcodes.ALOAD, 0);          // this
            super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/minecraft/world/entity/LivingEntity",
                "isAlive", "()Z", false);                  // 原始 isAlive（栈顶 boolean）
            super.visitVarInsn(Opcodes.ALOAD, 0);
            super.visitMethodInsn(Opcodes.INVOKESTATIC, ASM_UTIL,
                "specialIsAlive", "(ZLjava/lang/Object;)Z", false);
            super.visitInsn(Opcodes.IRETURN);
        }
    }

    /** isDeadOrDying 注入。 */
    private static class IsDeadOrDyingAdapter extends MethodVisitor {

        public IsDeadOrDyingAdapter(MethodVisitor mv) {
            super(Opcodes.ASM9, mv);
        }

        @Override
        public void visitCode() {
            super.visitCode();
            super.visitVarInsn(Opcodes.ALOAD, 0);
            super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "net/minecraft/world/entity/LivingEntity",
                "isDeadOrDying", "()Z", false);
            super.visitVarInsn(Opcodes.ALOAD, 0);
            super.visitMethodInsn(Opcodes.INVOKESTATIC, ASM_UTIL,
                "specialIsDeadOrDying", "(ZLjava/lang/Object;)Z", false);
            super.visitInsn(Opcodes.IRETURN);
        }
    }
}
