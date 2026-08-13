package net.minecraft.client.yiz.agent;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.security.ProtectionDomain;
import java.util.concurrent.ConcurrentHashMap;

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
                ClassWriter cw = newFrameClassWriter(cr, loader);
                ClassVisitor cv = new HealthClassVisitor(cw, className, superName, isEntity, true, modified, loader);
                cr.accept(cv, ClassReader.EXPAND_FRAMES);
                return modified[0] ? cw.toByteArray() : null;
            } catch (Throwable frameFail) {
                // 帧重算失败 → COMPUTE_MAXS 安全模式：关闭调用点包装（调用点插入会改栈深，MAXS 不重算帧可能 VerifyError）
                ClassWriter cw2 = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
                ClassVisitor cv2 = new HealthClassVisitor(cw2, className, superName, isEntity, false, modified, loader);
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

    // ==================== 子类判定（学 Trial isSubclass 走类层次） ====================

    private static final ConcurrentHashMap<String, Boolean> SUBCLASS_CACHE = new ConcurrentHashMap<>();

    /**
     * owner（INVOKEVIRTUAL/INVOKEINTERFACE 的静态接收者类型）是否为 LivingEntity 子类。
     * 调用点包装不能只看 {@code owner == LivingEntity}——真实字节码里血量调用的静态接收者
     * 常是 Mob/Monster/Animal/Player/各模组实体子类（如 {@code this.getHealth()} 的 owner
     * 就是当前类），只看 LivingEntity 会漏掉这些调用点。Trial 用 isSubclass 走类层次，此处对齐。
     */
    private static boolean isLivingEntitySubclass(ClassLoader loader, String ownerInternalName) {
        if (LIVING_ENTITY.equals(ownerInternalName)) return true;
        Boolean cached = SUBCLASS_CACHE.get(ownerInternalName);
        if (cached != null) return cached;
        boolean result = resolveSubclass(loader, ownerInternalName);
        SUBCLASS_CACHE.put(ownerInternalName, result);
        return result;
    }

    /**
     * 读 .class 资源走 superName 链（不加载类，避免 transform 期间 Class.forName 重入/死锁）。
     * 每个节点用「定义 loader + 线程上下文 loader」双源读：SecureJarClassLoader 的 getResourceAsStream
     * 只搜本 jar（找不到 PathfinderMob 等游戏父类），上下文 loader 能找游戏类但可能找不到模组类，
     * 两者互补才能走完「模组实体 → Mob → LivingEntity」整条链。单用 loader 会在第二跳断裂 → 子类判定漏判。
     */
    private static boolean resolveSubclass(ClassLoader loader, String ownerInternalName) {
        String current = ownerInternalName;
        try {
            while (current != null && !"java/lang/Object".equals(current)) {
                if (LIVING_ENTITY.equals(current)) return true;
                String superName = readSuperName(loader, current);
                if (superName == null) return false;
                current = superName;
            }
        } catch (Throwable ignored) {
            return false;
        }
        return false;
    }

    private static String readSuperName(ClassLoader loader, String internalName) {
        ClassLoader ctx = Thread.currentThread().getContextClassLoader();
        for (ClassLoader cl : new ClassLoader[]{ loader, ctx }) {
            if (cl == null) continue;
            try {
                InputStream is = cl.getResourceAsStream(internalName + ".class");
                if (is == null) continue;
                try {
                    return new ClassReader(is).getSuperName();
                } finally {
                    is.close();
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    /**
     * COMPUTE_FRAMES 的 ClassWriter：覆盖 getCommonSuperClass 用「被 transform 类的 loader」解析公共父类。
     * 默认 ClassWriter 用 agent 隔离 classloader，解析不到 Minecraft/模组类 → COMPUTE_FRAMES 抛异常 →
     * 回退 COMPUTE_MAXS 且关闭调用点包装（正是 Boss 血条/remove 不被钳制的根因）。
     * 注意不能用 Class.forName（会触发类加载 → 与 JPMS 模块重复定义 LinkageError），改走 .class 资源父类链。
     */
    private static ClassWriter newFrameClassWriter(ClassReader cr, ClassLoader loader) {
        return new ClassWriter(cr, ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String type1, String type2) {
                return commonSuperViaResources(loader, type1, type2);
            }
        };
    }

    /** 走 .class 资源父类链求公共父类（不 Class.forName，避免 transform 期间递归加载/重复定义）。 */
    private static String commonSuperViaResources(ClassLoader loader, String type1, String type2) {
        if (type1.equals(type2)) return type1;
        java.util.List<String> chain2 = superChain(loader, type2);
        for (String c : superChain(loader, type1)) {
            if (chain2.contains(c)) return c;
        }
        return "java/lang/Object";
    }

    private static java.util.List<String> superChain(ClassLoader loader, String internalName) {
        java.util.List<String> chain = new java.util.ArrayList<>();
        String current = internalName;
        int guard = 0;
        while (current != null && guard++ < 64) {
            chain.add(current);
            if ("java/lang/Object".equals(current)) break;
            String sn = readSuperName(loader, current);
            if (sn == null) break;
            current = sn;
        }
        return chain;
    }

    // ==================== 方法注入 ====================

    private static class HealthClassVisitor extends ClassVisitor {

        private final String className;
        private final String superName;
        private final boolean isEntity;
        private final boolean allowCallSite;
        private final boolean[] modified;
        private final ClassLoader loader;

        public HealthClassVisitor(ClassVisitor cv, String className, String superName,
                                  boolean isEntity, boolean allowCallSite, boolean[] modified,
                                  ClassLoader loader) {
            super(Opcodes.ASM9, cv);
            this.className = className;
            this.superName = superName;
            this.isEntity = isEntity;
            this.allowCallSite = allowCallSite;
            this.modified = modified;
            this.loader = loader;
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
            MethodVisitor cv = allowCallSite ? new CallSiteAdapter(mv, modified, loader) : mv;
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

    /** 调用点包装：方法内调用 getHealth/isAlive/isDeadOrDying 处插 DUP/SWAP/INVOKESTATIC 裁决，覆盖第三方调用。
     *  接收者类型判定走 isLivingEntitySubclass（子类层次），不只看 owner == LivingEntity。 */
    private static class CallSiteAdapter extends MethodVisitor {

        private final boolean[] modified;
        private final ClassLoader loader;

        public CallSiteAdapter(MethodVisitor mv, boolean[] modified, ClassLoader loader) {
            super(Opcodes.ASM9, mv);
            this.modified = modified;
            this.loader = loader;
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
            boolean virtual = opcode == Opcodes.INVOKEVIRTUAL || opcode == Opcodes.INVOKEINTERFACE;
            if (virtual && isLivingEntitySubclass(loader, owner)) {
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
