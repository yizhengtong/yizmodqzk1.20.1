package net.minecraft.client.yiz.tool.key;

import net.minecraft.client.yiz.core.asm.AgentBridge;
import sun.misc.Unsafe;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 目标 jar 模组「已加载类」枚举器 —— KeyHunter 四步配方的第 1 步。
 *
 * <p>两条路径，均按可配置包前缀过滤（前缀来自 {@code /yiz key scan <前缀...>}，
 * 代码内不含任何目标模组的硬编码名字）：</p>
 * <ul>
 *   <li><b>首选</b>：{@link Instrumentation#getAllLoadedClasses()}（经 {@link AgentBridge}）。</li>
 *   <li><b>兜底</b>（无 agent）：Unsafe 直读 {@code java.lang.ClassLoader.classes}
 *       （{@code Vector<Class<?>>}）→ {@code Vector.elementData}，沿「上下文加载器 + 父链 +
 *       所有线程上下文加载器」纯内存枚举 —— 不加载新类、不触发 {@code <clinit>}、无反射帧。</li>
 * </ul>
 */
@SuppressWarnings("removal")
public final class LoadedClassEnumerator {

    private static final Unsafe U = UnsafeAccess.get();

    // ClassLoader.classes（Vector<Class<?>>）与 Vector 内部结构的一次性定位
    private static final long CLASSES_OFFSET;
    private static final long VECTOR_DATA_OFFSET;
    private static final long VECTOR_COUNT_OFFSET;

    static {
        long co = -1, vd = -1, vc = -1;
        if (U != null) {
            try {
                Field f = ClassLoader.class.getDeclaredField("classes");
                co = U.objectFieldOffset(f);
            } catch (Throwable ignored) {}
            try {
                Field d = java.util.Vector.class.getDeclaredField("elementData");
                Field c = java.util.Vector.class.getDeclaredField("elementCount");
                vd = U.objectFieldOffset(d);
                vc = U.objectFieldOffset(c);
            } catch (Throwable ignored) {}
        }
        CLASSES_OFFSET = co;
        VECTOR_DATA_OFFSET = vd;
        VECTOR_COUNT_OFFSET = vc;
    }

    private LoadedClassEnumerator() {}

    /** 枚举所有已加载且类名以任一前缀开头的类。 */
    public static List<Class<?>> classesIn(String... packagePrefixes) {
        List<Class<?>> out = new ArrayList<>();

        // 路径 1：agent 已挂载 → Instrumentation 全量枚举
        Instrumentation inst = null;
        try {
            inst = AgentBridge.getInstrumentation();
        } catch (Throwable ignored) {}
        if (inst != null) {
            for (Class<?> c : inst.getAllLoadedClasses()) {
                if (matches(c.getName(), packagePrefixes)) out.add(c);
            }
            return out;
        }

        // 路径 2：无 agent → ClassLoader.classes 纯内存枚举
        for (ClassLoader cl : collectLoaders()) {
            dumpLoaderClasses(cl, packagePrefixes, out);
        }
        return out;
    }

    private static boolean matches(String className, String[] prefixes) {
        if (className == null) return false;
        for (String p : prefixes) {
            if (p != null && !p.isEmpty() && className.startsWith(p)) return true;
        }
        return false;
    }

    /** 收集候选加载器：上下文加载器 + 父链 + 所有存活线程的上下文加载器 + 系统加载器。 */
    private static Set<ClassLoader> collectLoaders() {
        Set<ClassLoader> loaders = new LinkedHashSet<>();
        try {
            ClassLoader ctx = Thread.currentThread().getContextClassLoader();
            for (ClassLoader cl = ctx; cl != null; cl = cl.getParent()) loaders.add(cl);
        } catch (Throwable ignored) {}
        try {
            for (Thread t : Thread.getAllStackTraces().keySet()) {
                try {
                    ClassLoader cl = t.getContextClassLoader();
                    if (cl != null) for (ClassLoader p = cl; p != null; p = p.getParent()) loaders.add(p);
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        try { loaders.add(ClassLoader.getSystemClassLoader()); } catch (Throwable ignored) {}
        return loaders;
    }

    /** Unsafe 直读某加载器 classes 向量中的类（无反射帧、无访问检查）。 */
    private static void dumpLoaderClasses(ClassLoader loader, String[] prefixes, List<Class<?>> out) {
        if (loader == null || U == null) return;
        if (CLASSES_OFFSET < 0 || VECTOR_DATA_OFFSET < 0 || VECTOR_COUNT_OFFSET < 0) return;
        try {
            Object vector = U.getObject(loader, CLASSES_OFFSET);
            if (vector == null) return;
            Object[] data = (Object[]) U.getObject(vector, VECTOR_DATA_OFFSET);
            int count = U.getInt(vector, VECTOR_COUNT_OFFSET);
            if (data == null) return;
            int n = Math.min(count, data.length);
            for (int i = 0; i < n; i++) {
                Object o = data[i];
                if (o instanceof Class<?> c && matches(c.getName(), prefixes)) out.add(c);
            }
        } catch (Throwable ignored) {}
    }
}
