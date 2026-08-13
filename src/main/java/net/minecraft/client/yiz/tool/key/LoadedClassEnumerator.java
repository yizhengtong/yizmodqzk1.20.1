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
 * <p>均按可配置包前缀过滤（前缀来自 {@code /yiz key scan <前缀...>}，
 * 代码内不含任何目标模组的硬编码名字）：</p>
 * <ul>
 *   <li><b>首选（唯一可靠）</b>：{@link Instrumentation#getAllLoadedClasses()}（经 {@link AgentBridge}）。</li>
 *   <li><b>无 agent 兜底（尽力而为）</b>：Unsafe 直读 {@code ClassLoader.classes} 向量。但
 *       JDK 9+ 已移除该字段（offset=-1，读恒 0），且 Forge 1.20.1 是单一
 *       {@code TransformingClassLoader}（无每 mod 子加载器），故此路径在 JDK 17 下实际不可用，
 *       枚举必须依赖 agent 的 Instrumentation。</li>
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

    /** 枚举结果（含诊断信息：来源、检查的加载器数、可见类总数）。 */
    public static final class EnumerationResult {
        public final List<Class<?>> classes;
        public final String source;
        public final int loadersInspected;
        public final int totalClassesSeen;

        EnumerationResult(List<Class<?>> classes, String source, int loadersInspected, int totalClassesSeen) {
            this.classes = classes;
            this.source = source;
            this.loadersInspected = loadersInspected;
            this.totalClassesSeen = totalClassesSeen;
        }
    }

    /** 枚举所有已加载且类名以任一前缀开头的类。 */
    public static EnumerationResult classesIn(String... packagePrefixes) {
        // 路径 1：agent 已挂载 → Instrumentation 全量枚举
        try {
            Instrumentation inst = AgentBridge.getInstrumentation();
            if (inst != null) {
                List<Class<?>> out = new ArrayList<>();
                int seen = 0;
                for (Class<?> c : inst.getAllLoadedClasses()) {
                    if (c == null) continue;
                    seen++;
                    if (matches(c.getName(), packagePrefixes)) out.add(c);
                }
                return new EnumerationResult(out, "instrumentation", 0, seen);
            }
        } catch (Throwable ignored) {}

        // 路径 2：无 agent → 线程上下文加载器（Forge 1.20.1 所有 mod 类在单一 TransformingClassLoader 下，
        // 游戏主线程 context loader 即它，不存在「每 mod 独立子加载器」）。
        // 注意：JDK 9+ 已移除 ClassLoader.classes 字段，下方 Unsafe 纯内存枚举定位不到字段（offset=-1）
        // → 返回 0，故无 agent 时本兜底在 JDK 17 下实际不可用，枚举只能依赖路径 1 的 Instrumentation。
        Set<ClassLoader> loaders = new LinkedHashSet<>();
        loaders.addAll(collectThreadLoaders());

        List<Class<?>> out = new ArrayList<>();
        int total = 0;
        for (ClassLoader cl : loaders) {
            total += dumpLoaderClasses(cl, packagePrefixes, out);
        }
        String source = loaders.isEmpty() ? "无可用枚举源" : "loader-walk(modlist+threads)";
        return new EnumerationResult(out, source, loaders.size(), total);
    }

    private static boolean matches(String className, String[] prefixes) {
        if (className == null) return false;
        for (String p : prefixes) {
            if (p != null && !p.isEmpty() && className.startsWith(p)) return true;
        }
        return false;
    }

    /** 无 agent 兜底：上下文加载器 + 父链 + 所有存活线程的上下文加载器 + 系统加载器。
     *  注意：Forge 1.20.1 所有 mod 类在单一 TransformingClassLoader 下，不存在「每 mod 独立子加载器」，
     *  旧版「ModList → 各 mod ClassLoader」枚举方向不成立，已移除。 */
    private static Set<ClassLoader> collectThreadLoaders() {
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

    /** Unsafe 直读某加载器 classes 向量中的类（无反射帧、无访问检查），返回该加载器可见类总数。 */
    private static int dumpLoaderClasses(ClassLoader loader, String[] prefixes, List<Class<?>> out) {
        if (loader == null || U == null) return 0;
        if (CLASSES_OFFSET < 0 || VECTOR_DATA_OFFSET < 0 || VECTOR_COUNT_OFFSET < 0) return 0;
        try {
            Object vector = U.getObject(loader, CLASSES_OFFSET);
            if (vector == null) return 0;
            Object[] data = (Object[]) U.getObject(vector, VECTOR_DATA_OFFSET);
            int count = U.getInt(vector, VECTOR_COUNT_OFFSET);
            if (data == null) return 0;
            int n = Math.min(count, data.length);
            for (int i = 0; i < n; i++) {
                Object o = data[i];
                if (o instanceof Class<?> c && matches(c.getName(), prefixes)) out.add(c);
            }
            return n;
        } catch (Throwable ignored) {
            return 0;
        }
    }
}
