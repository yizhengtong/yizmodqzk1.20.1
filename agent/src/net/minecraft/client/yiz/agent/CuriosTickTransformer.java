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

/**
 * Curios curioTick 调用点改写（per-player 持续效果关闭）。
 *
 * <p>改写 CuriosEventHandler 的所有方法，在 {@code ICurio.curioTick(SlotContext)} 调用点
 * 插入检查：穿戴者玩家关闭了该饰品的 CURIO_TICK → 跳过 curioTick（饰品留在槽里但效果停）。</p>
 *
 * <p>按 curioTick 调用指令匹配（不依赖 lambda 方法名，Curios 升级不失效）；
 * ICurio/SlotContext 是第三方 API 类不混淆，dev/prod 字节码一致。</p>
 */
public class CuriosTickTransformer implements ClassFileTransformer {

    private static final String CURIOS_EVENT_HANDLER = "top/theillusivec4/curios/common/event/CuriosEventHandler";
    private static final String I_CURIO = "top/theillusivec4/curios/api/type/capability/ICurio";
    private static final String SLOT_CONTEXT = "top/theillusivec4/curios/api/SlotContext";
    private static final String HOOKS = "net/minecraft/client/yiz/itemcfg/CuriosAgentHooks";
    private static final String CURIO_TICK_DESC = "(L" + SLOT_CONTEXT + ";)V";

    public static volatile boolean transformed = false;

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer)
            throws IllegalClassFormatException {
        if (className == null || classfileBuffer == null) return null;
        if (!CURIOS_EVENT_HANDLER.equals(className)) return null;
        transformed = true;
        try {
            ClassReader cr = new ClassReader(classfileBuffer);
            ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_FRAMES) {
                @Override
                protected String getCommonSuperClass(String type1, String type2) {
                    return commonSuperViaResources(loader, type1, type2);
                }
            };
            boolean[] modified = {false};
            cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                    return new CurioTickCallAdapter(mv, modified);
                }
            }, ClassReader.EXPAND_FRAMES);
            if (modified[0]) {
                System.err.println("[CuriosAgent] curioTick 调用点已注入: " + className);
                return cw.toByteArray();
            }
            return null;
        } catch (Throwable t) {
            System.err.println("[CuriosAgent] TRANSFORM FAILED " + className + ": " + t.getMessage());
            return null;
        }
    }

    /** 在 curioTick(SlotContext) 调用点插入 per-player 跳过检查。 */
    private static class CurioTickCallAdapter extends MethodVisitor {

        private final boolean[] modified;

        CurioTickCallAdapter(MethodVisitor mv, boolean[] modified) {
            super(Opcodes.ASM9, mv);
            this.modified = modified;
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
            if (opcode == Opcodes.INVOKEINTERFACE && I_CURIO.equals(owner)
                    && "curioTick".equals(name) && CURIO_TICK_DESC.equals(descriptor)) {
                // 调用前栈：[ICurio, SlotContext]
                Label skip = new Label();
                Label end = new Label();
                super.visitInsn(Opcodes.DUP);                 // [ICurio, SlotContext, SlotContext]
                super.visitMethodInsn(Opcodes.INVOKESTATIC, HOOKS,
                    "shouldSkipCurioTick", "(L" + SLOT_CONTEXT + ";)Z", false); // [ICurio, SlotContext, bool]
                super.visitJumpInsn(Opcodes.IFNE, skip);      // bool=true → 跳过 curioTick
                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface); // 原调用
                super.visitJumpInsn(Opcodes.GOTO, end);
                super.visitLabel(skip);                       // [ICurio, SlotContext]
                super.visitInsn(Opcodes.POP);                 // 弹 SlotContext
                super.visitInsn(Opcodes.POP);                 // 弹 ICurio
                super.visitLabel(end);
                modified[0] = true;
                return;
            }
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
        }
    }

    // ==== 资源链 getCommonSuperClass（不 Class.forName，避免 transform 期间类加载重入） ====

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

    private static String readSuperName(ClassLoader loader, String internalName) {
        ClassLoader ctx = Thread.currentThread().getContextClassLoader();
        for (ClassLoader cl : new ClassLoader[]{loader, ctx}) {
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
}
