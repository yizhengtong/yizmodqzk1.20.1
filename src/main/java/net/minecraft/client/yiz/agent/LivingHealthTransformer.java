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
 *
 * <p>两层注入（返回值包装 + 调用点包装，均零递归）：
 * <ul>
 *   <li><b>方法自身返回值包装</b>：对 LivingEntity 及其子类的 getHealth/isAlive/isDeadOrDying/getMaxHealth，
 *       每个 return 前包装 special*（secure 读表 / delta 截断）。</li>
 *   <li><b>调用点包装</b>：对<b>所有类</b>的方法内 INVOKEVIRTUAL/INVOKEINTERFACE 调用
 *       getHealth/isAlive/isDeadOrDying 处插 DUP/SWAP/INVOKESTATIC 裁决——连"第三方调用血量"也被拦截。</li>
 * </ul>
 * 方法名同时认 official 名与 SRG 名（生产环境是 m_21223_ 等）。</p>
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

        // 所有非排除类都扫描：方法自身 FRETURN 包装（LivingEntity 及其子类）+ 调用点包装（所有类）
        boolean isEntity = LIVING_ENTITY.equals(className);

        transformed = true;
        try {
            Class<?> bridgeClass = Class.forName(BRIDGE_CLASS);
            bridgeClass.getMethod("markTransformed").invoke(null);
        } catch (Exception ignored) {}

        boolean[] modified = {false};
        try {
            ClassReader cr = new ClassReader(classfileBuffer);
            String superName = cr.getSuperName();
            try {
                ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_FRAMES);
                ClassVisitor cv = new HealthClassVisitor(cw, className, superName, isEntity, true, modified);
                cr.accept(cv, ClassReader.EXPAND_FRAMES);
                return modified[0] ? cw.toByteArray() : null;
            } catch (Throwable frameFail) {
                // 帧重算失败 → COMPUTE_MAXS 安全模式：关闭调用点包装（调用点插入会改栈深，MAXS 不重算帧可能 VerifyError）
                ClassWriter cw2 = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
                ClassVisitor cv2 = new HealthClassVisitor(cw2, className, superName, isEntity, false, modified);
                cr.accept(cv2, ClassReader.EXPAND_FRAMES);
                return modified[0] ? cw2.toByteArray() : null;
            }
        } catch (Throwable e) {
            System.err.println("[YizModQZK Agent] TRANSFORM FAILED for " + className.replace('/', '.')
                + ": " + e.getClass().getName() + " - " + e.getMessage());
            return null;
        }
    }

    // ==================== 排除名单 ====================

    private boolean isExcluded(String className) {
        // 辖界者：允许注入（通用不死守卫，用外部表覆盖 getHealth/isAlive/isDeadOrDying，
        // 对抗任何外部模组的字节码注入包裹）
        if ("net/minecraft/client/yiz/xian/entity/QuanshouzheEntity".equals(className)) return false;
        if (className.startsWith("net/minecraft/client/yiz")) return true; // 本模组其余类不注入
        if (className.startsWith("net/minecraft/client/player")) return true; // 客户端玩家由 Mixin 处理
        if (className.contains("$$")) return true; // Mixin 生成类
        return false;
    }

    // ==================== 方法注入 ====================

    private static class HealthClassVisitor extends ClassVisitor {

        private final String className;
        private final String superName;
        private final boolean isEntity;
        private final boolean allowCallSite;
        private final boolean[] modified;

        public HealthClassVisitor(ClassVisitor cv, String className, String superName,
                                  boolean isEntity, boolean allowCallSite, boolean[] modified) {
            super(Opcodes.ASM9, cv);
            this.className = className;
            this.superName = superName;
            this.isEntity = isEntity;
            this.allowCallSite = allowCallSite;
            this.modified = modified;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                         String signature, String[] exceptions) {
            MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
            if (mv == null) return null;
            // EntityTickList.forEach：Consumer.accept 调用点注入 shouldOverrideTick + tickOverride（强制双 tick）
            if ("net/minecraft/world/level/entity/EntityTickList".equals(className)
                    && ("forEach".equals(name) || "m_156910_".equals(name))
                    && "(Ljava/util/function/Consumer;)V".equals(descriptor)) {
                return new EntityTickListAdapter(mv, modified);
            }
            // ServerLevel.tick：末尾注入 updateLastTicks（每 tick 更新受保护实体 lastTickCount + 强制 tick）
            if ("net/minecraft/server/level/ServerLevel".equals(className)
                    && ("tick".equals(name) || "m_8793_".equals(name))
                    && "(Ljava/util/function/BooleanSupplier;)V".equals(descriptor)) {
                return new ServerTickAdapter(mv, modified);
            }
            // 调用点包装（包裹在最内层，扫描方法内所有血量调用指令）
            MethodVisitor cv = allowCallSite ? new CallSiteAdapter(mv, modified) : mv;
            // 方法自身返回值包装（仅当方法就是这些血量方法）
            if (("getHealth".equals(name) || "m_21223_".equals(name)) && "()F".equals(descriptor)) {
                return new GetHealthAdapter(cv, modified);
            }
            if (("getMaxHealth".equals(name) || "m_21233_".equals(name)) && "()F".equals(descriptor)) {
                return new GetMaxHealthAdapter(cv, modified);
            }
            if (("isAlive".equals(name) || "m_6084_".equals(name)) && "()Z".equals(descriptor)) {
                return new IsAliveAdapter(cv, modified);
            }
            if (("isDeadOrDying".equals(name) || "m_21224_".equals(name)) && "()Z".equals(descriptor)) {
                return new IsDeadOrDyingAdapter(cv, modified);
            }
            return cv;
        }
    }

    /** 调用点包装：方法内调用 getHealth/isAlive/isDeadOrDying 处插 DUP/SWAP/INVOKESTATIC 裁决，覆盖第三方调用。 */
    private static class CallSiteAdapter extends MethodVisitor {

        private final boolean[] modified;

        public CallSiteAdapter(MethodVisitor mv, boolean[] modified) {
            super(Opcodes.ASM9, mv);
            this.modified = modified;
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
            boolean virtual = opcode == Opcodes.INVOKEVIRTUAL || opcode == Opcodes.INVOKEINTERFACE;
            if (virtual && LIVING_ENTITY.equals(owner)) {
                if (("getHealth".equals(name) || "m_21223_".equals(name)) && "()F".equals(descriptor)) {
                    super.visitInsn(Opcodes.DUP);
                    super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                    super.visitInsn(Opcodes.SWAP);
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, ASM_UTIL,
                        "specialGetHealth", "(FLjava/lang/Object;)F", false);
                    modified[0] = true;
                    return;
                }
                if (("isAlive".equals(name) || "m_6084_".equals(name)) && "()Z".equals(descriptor)) {
                    super.visitInsn(Opcodes.DUP);
                    super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                    super.visitInsn(Opcodes.SWAP);
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, ASM_UTIL,
                        "specialIsAlive", "(ZLjava/lang/Object;)Z", false);
                    modified[0] = true;
                    return;
                }
                if (("isDeadOrDying".equals(name) || "m_21224_".equals(name)) && "()Z".equals(descriptor)) {
                    super.visitInsn(Opcodes.DUP);
                    super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                    super.visitInsn(Opcodes.SWAP);
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, ASM_UTIL,
                        "specialIsDeadOrDying", "(ZLjava/lang/Object;)Z", false);
                    modified[0] = true;
                    return;
                }
            }
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
        }
    }

    /** EntityTickList.forEach 的 Consumer.accept 调用点注入：受保护实体每 tick 跑两次（tickOverride）。 */
    private static class EntityTickListAdapter extends MethodVisitor {

        private final boolean[] modified;

        public EntityTickListAdapter(MethodVisitor mv, boolean[] modified) {
            super(Opcodes.ASM9, mv);
            this.modified = modified;
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
            if ((opcode == Opcodes.INVOKEINTERFACE || opcode == Opcodes.INVOKEVIRTUAL)
                    && "java/util/function/Consumer".equals(owner)
                    && "accept".equals(name) && "(Ljava/lang/Object;)V".equals(descriptor)) {
                // 调用前栈：[..., consumer, entity]
                Label skip = new Label();
                Label end = new Label();
                super.visitInsn(Opcodes.DUP);                                       // [..., consumer, entity, entity]
                super.visitMethodInsn(Opcodes.INVOKESTATIC, ASM_UTIL,
                    "shouldOverrideTick", "(Lnet/minecraft/world/entity/Entity;)Z", false); // [..., consumer, entity, bool]
                super.visitJumpInsn(Opcodes.IFGT, skip);                            // bool>0 → 走 tickOverride
                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface); // 原 accept：消费 consumer+entity
                super.visitJumpInsn(Opcodes.GOTO, end);
                super.visitLabel(skip);                                             // 栈：[..., consumer, entity]
                super.visitMethodInsn(Opcodes.INVOKESTATIC, ASM_UTIL,
                    "tickOverride", "(Ljava/util/function/Consumer;Lnet/minecraft/world/entity/Entity;)V", false);
                super.visitLabel(end);
                modified[0] = true;
                return;
            }
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
        }
    }

    /** ServerLevel.tick 末尾注入 updateLastTicks（每 tick 更新受保护实体 lastTickCount + 强制 tick）。 */
    private static class ServerTickAdapter extends MethodVisitor {

        private final boolean[] modified;

        public ServerTickAdapter(MethodVisitor mv, boolean[] modified) {
            super(Opcodes.ASM9, mv);
            this.modified = modified;
        }

        @Override
        public void visitInsn(int opcode) {
            if (opcode == Opcodes.RETURN) {
                super.visitVarInsn(Opcodes.ALOAD, 0);   // this (ServerLevel)
                super.visitMethodInsn(Opcodes.INVOKESTATIC, ASM_UTIL,
                    "updateLastTicks", "(Lnet/minecraft/server/level/ServerLevel;)V", false);
                modified[0] = true;
            }
            super.visitInsn(opcode);
        }
    }

    /**
     * getHealth 方法自身：每个 FRETURN 前包装 specialGetHealth(返回值, this)。
     * 不用方法开头 INVOKEVIRTUAL 取原始值（虚分派回自身 → 无限递归），返回值包装零递归。
     */
    private static class GetHealthAdapter extends MethodVisitor {

        private final boolean[] modified;

        public GetHealthAdapter(MethodVisitor mv, boolean[] modified) {
            super(Opcodes.ASM9, mv);
            this.modified = modified;
        }

        @Override
        public void visitInsn(int opcode) {
            if (opcode == Opcodes.FRETURN) {
                super.visitVarInsn(Opcodes.ALOAD, 0);      // this
                super.visitMethodInsn(Opcodes.INVOKESTATIC, ASM_UTIL,
                    "specialGetHealth", "(FLjava/lang/Object;)F", false);
                modified[0] = true;
            }
            super.visitInsn(opcode);
        }
    }

    /** getMaxHealth 方法自身：每个 FRETURN 前包装 specialGetMaxHealth(返回值, this)。 */
    private static class GetMaxHealthAdapter extends MethodVisitor {

        private final boolean[] modified;

        public GetMaxHealthAdapter(MethodVisitor mv, boolean[] modified) {
            super(Opcodes.ASM9, mv);
            this.modified = modified;
        }

        @Override
        public void visitInsn(int opcode) {
            if (opcode == Opcodes.FRETURN) {
                super.visitVarInsn(Opcodes.ALOAD, 0);      // this
                super.visitMethodInsn(Opcodes.INVOKESTATIC, ASM_UTIL,
                    "specialGetMaxHealth", "(FLjava/lang/Object;)F", false);
                modified[0] = true;
            }
            super.visitInsn(opcode);
        }
    }

    /** isAlive 方法自身：每个 IRETURN 前包装 specialIsAlive(返回值, this)。 */
    private static class IsAliveAdapter extends MethodVisitor {

        private final boolean[] modified;

        public IsAliveAdapter(MethodVisitor mv, boolean[] modified) {
            super(Opcodes.ASM9, mv);
            this.modified = modified;
        }

        @Override
        public void visitInsn(int opcode) {
            if (opcode == Opcodes.IRETURN) {
                super.visitVarInsn(Opcodes.ALOAD, 0);      // this
                super.visitMethodInsn(Opcodes.INVOKESTATIC, ASM_UTIL,
                    "specialIsAlive", "(ZLjava/lang/Object;)Z", false);
                modified[0] = true;
            }
            super.visitInsn(opcode);
        }
    }

    /** isDeadOrDying 方法自身：每个 IRETURN 前包装 specialIsDeadOrDying(返回值, this)。 */
    private static class IsDeadOrDyingAdapter extends MethodVisitor {

        private final boolean[] modified;

        public IsDeadOrDyingAdapter(MethodVisitor mv, boolean[] modified) {
            super(Opcodes.ASM9, mv);
            this.modified = modified;
        }

        @Override
        public void visitInsn(int opcode) {
            if (opcode == Opcodes.IRETURN) {
                super.visitVarInsn(Opcodes.ALOAD, 0);      // this
                super.visitMethodInsn(Opcodes.INVOKESTATIC, ASM_UTIL,
                    "specialIsDeadOrDying", "(ZLjava/lang/Object;)Z", false);
                modified[0] = true;
            }
            super.visitInsn(opcode);
        }
    }
}
