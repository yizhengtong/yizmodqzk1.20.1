package net.minecraft.client.yiz.tool.health;

import net.minecraft.client.yiz.core.asm.AgentBridge;
import net.minecraft.client.yiz.tool.key.UnsafeAccess;
import net.minecraft.world.entity.Entity;
import sun.misc.Unsafe;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 「编解码入口池」—— 把「跳过目标密码」做成纯类型 + 纯行为，而不是去反解任何具体算法。
 *
 * <p><b>候选从哪来（必要性规则，而不是"见过的形状"）</b>：要调用一个入口，我们能提供的实参只有三种 ——
 * ① 明文数值（自己造探针）、② 目标自己的载体对象、③ 有静态单例可取的同型对象。
 * 因此候选的必要条件是：</p>
 * <ul>
 *   <li>参数表 = 若干「数值位」+ 至多两个「第三方引用类型位」；</li>
 *   <li>返回 = 数值 / void / 第三方引用类型；</li>
 *   <li><b>元数、参数顺序、静态还是实例一律不限</b>；<b>构造器同等对待</b>。</li>
 * </ul>
 *
 * <p>形状只用来<b>排序</b>（先试最像编解码入口的），<b>绝不用来排除</b>；能不能用由行为裁决：
 * 造出对象 → 用目标自己的读出口读回 → 必须等于目标明文。</p>
 *
 * <p>因此换算法、换盐、换标签、换字段名，或者写成多参数工厂 / Builder / 构造器 / 单例实例方法，
 * 都照样进候选 —— 搜索空间由"调用需要什么"推导，与目标怎么写无关。代价是候选变多，
 * 由排序 + "上次命中入口优先"缓存把常见情形压到 1~2 次调用。</p>
 */
public final class ExternalCodecPool {

    private static final org.slf4j.Logger LOGGER = net.minecraft.client.yiz.tizMod.LOGGER;

    /** 试错上限：造对象 → 读回 → 修正的迭代次数（随机盐/随机标签也收敛）。 */
    private static final int TRIAL_LIMIT = 8;
    /** 单个入口允许的「第三方引用参数」上限（再多我们无法同时提供实参）。 */
    private static final int MAX_REF_PARAMS = 2;

    private ExternalCodecPool() {}

    // ==================== 加载期全量索引 ====================

    /** 一次全类扫描得到的候选句柄（只存句柄，不调用任何东西）。 */
    public static final class Index {
        final List<Method> numericOnly = new ArrayList<>();               // 参数全为数值
        final Map<Class<?>, List<Method>> byRefParam = new HashMap<>();    // 恰含第三方引用参数 → 按类型分桶
        final List<Method> instanceDecode = new ArrayList<>();             // 实例无参 → 数值
        final List<Constructor<?>> ctors = new ArrayList<>();
        final List<Method> entityRead = new ArrayList<>();
        final List<Method> entityWrite = new ArrayList<>();
        final int classCount;
        /** 代次：每次类表变化重建时 +1。句柄缓存据此失效重解析。 */
        final int gen;
        long buildMs;

        Index(int classCount, int gen) {
            this.classCount = classCount;
            this.gen = gen;
        }

        public String describe() {
            int refs = 0;
            for (List<Method> l : byRefParam.values()) refs += l.size();
            return "类数=" + classCount + " 用时=" + buildMs + "ms 数值参数入口=" + numericOnly.size()
                    + " 引用参数入口=" + refs + " 实例解码=" + instanceDecode.size()
                    + " 构造器=" + ctors.size() + " 实体读口=" + entityRead.size()
                    + " 实体写口=" + entityWrite.size();
        }
    }

    private static volatile Index INDEX;
    private static volatile boolean TICKER_STARTED;
    private static final java.util.concurrent.atomic.AtomicInteger GENERATION = new java.util.concurrent.atomic.AtomicInteger();
    private static final Object LOCK = new Object();

    /** 加载期预热：后台把全类方法/构造器扫一遍。幂等，可重复调用。 */
    public static void warmupAsync() {
        startTickerOnce();
    }

    private static void startTickerOnce() {
        if (TICKER_STARTED) return;
        synchronized (LOCK) {
            if (TICKER_STARTED) return;
            TICKER_STARTED = true;
            ScheduledExecutorService t = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread th = new Thread(r, "yiz-codec-pool");
                th.setDaemon(true);
                th.setPriority(Thread.MIN_PRIORITY);
                return th;
            });
            t.scheduleWithFixedDelay(ExternalCodecPool::refreshSafely, 0L, 10_000L, TimeUnit.MILLISECONDS);
        }
    }

    private static void refreshSafely() {
        try {
            refresh();
        } catch (Throwable t) {
            LOGGER.warn("[CodecPool] 后台扫描异常（保留旧池）: {}", t.toString());
        }
    }

    private static void refresh() {
        Instrumentation inst = AgentBridge.getInstrumentation();
        if (inst == null) return;                       // agent 未就绪 → 下次再试
        Class<?>[] all = inst.getAllLoadedClasses();
        if (all == null || all.length == 0) return;
        Index old = INDEX;
        if (old != null && old.classCount == all.length) return;   // 类表没变 → 不重扫
        long t0 = System.nanoTime();
        Index idx = new Index(all.length, GENERATION.incrementAndGet());
        for (Class<?> c : all) {
            if (!interesting(c)) continue;
            try {
                for (Method m : c.getDeclaredMethods()) indexMethod(m, idx);
            } catch (Throwable ignored) {}
            try {
                for (Constructor<?> k : c.getDeclaredConstructors()) {
                    if (indexable(k.getParameterTypes(), void.class, k.getModifiers())) idx.ctors.add(k);
                }
            } catch (Throwable ignored) {}
        }
        idx.buildMs = (System.nanoTime() - t0) / 1_000_000L;
        INDEX = idx;
        LOGGER.info("[CodecPool] 候选池就绪: {}", idx.describe());
    }

    /** 只扫「可能与血量存储有关」的第三方类：排除 JDK / 引擎 / 自家包。 */
    private static boolean interesting(Class<?> c) {
        if (c == null || c.isPrimitive() || c.isArray() || c.isSynthetic() || c.isAnnotation()) return false;
        String n = c.getName();
        if (n.startsWith("java.") || n.startsWith("javax.") || n.startsWith("jdk.") || n.startsWith("sun.")
                || n.startsWith("com.sun.") || n.startsWith("com.mojang.") || n.startsWith("net.minecraft.")
                || n.startsWith("net.minecraftforge.") || n.startsWith("org.spongepowered.")
                || n.startsWith("io.netty.") || n.startsWith("org.apache.") || n.startsWith("it.unimi.")
                || n.startsWith("org.lwjgl.") || n.startsWith("com.google.") || n.startsWith("org.slf4j.")
                || n.startsWith("org.objectweb.") || n.startsWith("cpw.mods.")) {
            return false;
        }
        return true;
    }

    private static void indexMethod(Method m, Index idx) {
        int mod = m.getModifiers();
        if (m.isSynthetic() || m.isBridge() || Modifier.isAbstract(mod)) return;
        Class<?>[] ps = m.getParameterTypes();
        Class<?> rt = m.getReturnType();

        // 实体读/写口：模块自己对实体读写血量的入口（现场用小步探针验证语义）
        if (Modifier.isStatic(mod) && ps.length >= 1 && entityParam(ps[0])) {
            boolean restNumeric = true;
            for (int i = 1; i < ps.length; i++) if (!numeric(ps[i])) restNumeric = false;
            if (restNumeric) {
                if (ps.length == 1 && numeric(rt)) idx.entityRead.add(m);
                else idx.entityWrite.add(m);
                return;
            }
        }
        if (!indexable(ps, rt, mod)) return;
        if (!Modifier.isStatic(mod) && ps.length == 0 && numeric(rt)) {
            idx.instanceDecode.add(m);                  // 载体自己的无参读出口
            return;
        }
        int refs = 0;
        Class<?> refType = null;
        for (Class<?> p : ps) {
            if (numeric(p)) continue;
            refs++;
            refType = p;
        }
        if (refs == 0) idx.numericOnly.add(m);
        else if (refType != null) idx.byRefParam.computeIfAbsent(refType, k -> new ArrayList<>()).add(m);
    }

    /**
     * 候选必要性判据：参数只能是「数值」或「第三方引用类型」（引用位 ≤ {@link #MAX_REF_PARAMS}），
     * 返回值只能是数值 / void / 第三方引用类型。元数、顺序、静态与否都不限。
     */
    private static boolean indexable(Class<?>[] ps, Class<?> rt, int mod) {
        if (Modifier.isAbstract(mod)) return false;
        int refs = 0;
        for (Class<?> p : ps) {
            if (numeric(p)) continue;
            if (p.isPrimitive() || p == String.class || p.isEnum() || !thirdParty(p)) return false;
            if (++refs > MAX_REF_PARAMS) return false;
        }
        return numeric(rt) || rt == void.class || thirdParty(rt);
    }

    // ==================== 端口（可调用入口） ====================

    /**
     * 一个可调用入口：方法或构造器 + 参数填法（构建期就解析好）。
     *
     * <p>{@link #args} 与 {@link #params} 按下标对齐：{@code null} = 数值位（调用时填探针值）、
     * {@link #CARRIER} = 载体位（填当前载体）、其它 = 固定实参（静态单例）。</p>
     */
    private static final class Port {
        static final Object CARRIER = new Object();

        final Method method;          // 与 ctor 二选一
        final Constructor<?> ctor;
        final Class<?>[] params;
        final Object[] args;
        final Object receiver;        // 静态为 null；实例为「声明类的静态单例」
        final boolean receiverIsCarrier;
        final Class<?> returnType;
        final int score;

        Port(Method method, Constructor<?> ctor, Class<?>[] params, Object[] args,
             Object receiver, boolean receiverIsCarrier, Class<?> returnType, int score) {
            this.method = method;
            this.ctor = ctor;
            this.params = params;
            this.args = args;
            this.receiver = receiver;
            this.receiverIsCarrier = receiverIsCarrier;
            this.returnType = returnType;
            this.score = score;
        }

        boolean touchesCarrier() {
            if (receiverIsCarrier) return true;
            for (Object a : args) if (a == CARRIER) return true;
            return false;
        }

        /** 调用：数值位统一填 {@code x}，载体位填 {@code carrier}。 */
        Object invoke(Object carrier, double x) throws Exception {
            Object recv = receiverIsCarrier ? carrier : receiver;
            Object[] a = new Object[params.length];
            for (int i = 0; i < params.length; i++) {
                Object f = args[i];
                a[i] = (f == null) ? coerce(x, params[i]) : (f == CARRIER ? carrier : f);
            }
            if (ctor != null) {
                ctor.setAccessible(true);
                return ctor.newInstance(a);
            }
            method.setAccessible(true);
            return method.invoke(recv, a);
        }

        String signature() {
            StringBuilder sb = new StringBuilder();
            sb.append(receiverIsCarrier ? "self " : (receiver != null ? "inst " : "static "));
            sb.append(ctor != null ? "<init>" : method.getName()).append('(');
            for (int i = 0; i < params.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(args[i] == CARRIER ? "载体" : params[i].getSimpleName());
            }
            sb.append(")->").append(returnType == null ? "?" : returnType.getSimpleName());
            return sb.toString();
        }
    }

    // ==================== 句柄 ====================

    private static final Map<Class<?>, Cached> HANDLES = new ConcurrentHashMap<>();

    /** 缓存的句柄 + 它是在哪一代类表上解析出来的（类表更新后自动失效重解析）。 */
    private static final class Cached {
        final Handle handle;      // 可为 null（负缓存）
        final int gen;

        Cached(Handle handle, int gen) {
            this.handle = handle;
            this.gen = gen;
        }
    }

    /** 取某个载体类型的入口句柄。类表（代次）变化后自动重新解析，避免"类没加载完就定死候选表"。 */
    public static Handle handleFor(Class<?> carrierType) {
        if (carrierType == null) return null;
        Index idx = INDEX;
        int gen = idx == null ? -1 : idx.gen;
        Cached c = HANDLES.get(carrierType);
        if (c != null && c.gen == gen) return c.handle;
        synchronized (HANDLES) {
            c = HANDLES.get(carrierType);
            if (c != null && c.gen == gen) return c.handle;
            Handle h = resolve(carrierType);
            HANDLES.put(carrierType, new Cached(h, gen));
            if (h != null) {
                LOGGER.info("[CodecPool] {} 入口句柄: {}", carrierType.getName(), h.describe());
                LOGGER.info("[CodecPool] {} 候选详单: {}", carrierType.getName(), h.describeCandidates());
            } else {
                LOGGER.info("[CodecPool] {} 无可用入口（读取侧仍可单独提试）", carrierType.getName());
            }
            return h;
        }
    }

    private static Handle resolve(Class<?> carrierType) {
        List<Port> decoders = new ArrayList<>();
        List<Port> writers = new ArrayList<>();
        collect(carrierType, decoders, writers);
        int fromIndex = decoders.size() + writers.size();
        // 无条件补扫「与该载体直接相关的类」：载体自己的方法与它的外层类常是真正的编解码入口，
        // 而加载期索引可能是在那些类加载完成之前建的（早建的部分不含它们）。
        localScan(carrierType, decoders, writers);
        if (decoders.isEmpty() || writers.isEmpty()) return null;
        sort(decoders);
        sort(writers);
        LOGGER.info("[CodecPool] {} 候选来源: 索引={} 局部补扫={}", carrierType.getSimpleName(),
                fromIndex, decoders.size() + writers.size() - fromIndex);
        return new Handle(carrierType, decoders, writers);
    }

    /** 从加载期索引取与该载体相关的入口。 */
    private static void collect(Class<?> carrierType, List<Port> decoders, List<Port> writers) {
        Index idx = INDEX;
        if (idx == null) return;
        for (Method m : idx.instanceDecode) {
            if (m.getDeclaringClass().isAssignableFrom(carrierType)) addPort(carrierType, m, null, decoders, writers);
        }
        for (Method m : idx.numericOnly) addPort(carrierType, m, null, decoders, writers);
        for (Map.Entry<Class<?>, List<Method>> e : idx.byRefParam.entrySet()) {
            if (!e.getKey().isAssignableFrom(carrierType) && !carrierType.isAssignableFrom(e.getKey())) continue;
            for (Method m : e.getValue()) addPort(carrierType, m, null, decoders, writers);
        }
        for (Constructor<?> k : idx.ctors) {
            if (!carrierType.isAssignableFrom(k.getDeclaringClass())) continue;
            addPort(carrierType, null, k, decoders, writers);
        }
    }

    /** 池未覆盖（agent 未就绪 / 类加载晚于扫描）→ 只扫与该载体直接相关的类，判据完全一致。 */
    private static void localScan(Class<?> carrierType, List<Port> decoders, List<Port> writers) {
        LinkedHashSet<Class<?>> scope = new LinkedHashSet<>();
        for (Class<?> k = carrierType; k != null && k != Object.class; k = k.getSuperclass()) scope.add(k);
        for (Class<?> i : carrierType.getInterfaces()) scope.add(i);
        Class<?> decl = carrierType.getDeclaringClass();
        if (decl != null) {
            for (Class<?> k = decl; k != null && k != Object.class; k = k.getSuperclass()) scope.add(k);
        }
        for (Class<?> c : scope) {
            try {
                for (Method m : c.getDeclaredMethods()) {
                    if (m.isSynthetic() || m.isBridge() || Modifier.isAbstract(m.getModifiers())) continue;
                    if (!indexable(m.getParameterTypes(), m.getReturnType(), m.getModifiers())) continue;
                    addPort(carrierType, m, null, decoders, writers);
                }
                for (Constructor<?> k : c.getDeclaredConstructors()) {
                    if (!indexable(k.getParameterTypes(), void.class, k.getModifiers())) continue;
                    if (!carrierType.isAssignableFrom(k.getDeclaringClass())) continue;
                    addPort(carrierType, null, k, decoders, writers);
                }
            } catch (Throwable ignored) {}
        }
    }

    /** 把方法/构造器绑定成端口：解析每个参数位，按角色分派到读出表 / 写入表。 */
    private static void addPort(Class<?> carrierType, Method m, Constructor<?> k,
                                List<Port> decoders, List<Port> writers) {
        Class<?>[] ps = (k != null) ? k.getParameterTypes() : m.getParameterTypes();
        Class<?> rt = (k != null) ? k.getDeclaringClass() : m.getReturnType();
        int mod = (k != null) ? k.getModifiers() : m.getModifiers();
        if (!indexable(ps, (k != null) ? void.class : rt, mod)) return;

        Object[] args = new Object[ps.length];
        for (int i = 0; i < ps.length; i++) {
            Class<?> p = ps[i];
            if (numeric(p)) {
                args[i] = null;                                   // 数值位
            } else if (p.isAssignableFrom(carrierType)) {
                args[i] = Port.CARRIER;                           // 载体位
            } else {
                Object s = singletonOf(p);                        // 同型单例位
                if (s == null) return;                            // 提供不了 → 该候选不可用
                args[i] = s;
            }
        }
        Object receiver = null;
        boolean receiverIsCarrier = false;
        if (k == null && !Modifier.isStatic(mod)) {
            if (m.getDeclaringClass().isAssignableFrom(carrierType)) {
                receiverIsCarrier = true;                         // 载体自己的方法 → 接收者就是载体
            } else {
                Object s = singletonOf(m.getDeclaringClass());
                if (s == null) return;                            // 实例方法但拿不到接收者 → 不可用
                receiver = s;
            }
        }
        boolean numericReturn = numeric(rt);
        Port port = new Port(m, k, ps, args, receiver, receiverIsCarrier, rt,
                score(k != null, m, ps, rt, receiverIsCarrier, numericReturn));

        boolean returnsNumeric = numeric(rt);
        boolean returnsCarrier = !returnsNumeric && rt != void.class
                && (rt.isAssignableFrom(carrierType) || carrierType.isAssignableFrom(rt));
        boolean touches = port.touchesCarrier();
        if (returnsNumeric && touches) addOnce(decoders, port);                 // 读出口
        if (!touches) {
            if (returnsCarrier) addOnce(writers, port);                          // 造对象（工厂/构造器）
        } else if (returnsCarrier) {
            addOnce(writers, port);                                             // 从载体派生新对象 / 就地改并返回新对象
        } else {
            addOnce(writers, port);                                             // 就地改（void 或返回数值）
        }
    }

    private static void addOnce(List<Port> list, Port p) {
        for (Port x : list) if (x.method == p.method && x.ctor == p.ctor && x.receiverIsCarrier == p.receiverIsCarrier) return;
        list.add(p);
    }

    /**
     * 排序：先试「最像编解码入口」的。纯排序，不做排除 ——
     * 参数少、明文值参（float）靠前、返回对象的更像"造密文"、载体自己的方法更可信；
     * 返回数值又只碰载体的更可能是纯读出口（当写入口试会白跑），排在后面。
     */
    private static int score(boolean ctor, Method m, Class<?>[] ps, Class<?> rt,
                             boolean receiverIsCarrier, boolean numericReturn) {
        int s = 100 - ps.length * 10;
        if (!ctor) {
            if (Modifier.isPublic(m.getModifiers())) s += 5;
            if (receiverIsCarrier) s += 3;
        }
        for (int i = 0; i < ps.length; i++) {
            if (ps[i] == float.class) s += 6 - i;
            else if (numeric(ps[i])) s += 2;
        }
        if (rt != void.class && !numeric(rt)) s += 4;
        if (numericReturn) s -= 25;
        return s;
    }

    private static void sort(List<Port> ports) {
        ports.sort((a, b) -> Integer.compare(b.score, a.score));
    }

    /** 载体类型的读写句柄。 */
    public static final class Handle {
        private final Class<?> carrierType;
        private final List<Port> decoders;
        private final List<Port> writers;
        /** 上次试成功的入口（提试成本只在首次付）。 */
        private volatile Port lastGoodWriter;

        Handle(Class<?> carrierType, List<Port> decoders, List<Port> writers) {
            this.carrierType = carrierType;
            this.decoders = decoders;
            this.writers = writers;
        }

        public Class<?> carrierType() { return carrierType; }

        public int decoderCount() { return decoders.size(); }

        /** 用第 {@code idx} 个入口读出明文；读不出（异常 / 非有限数）返回 null。 */
        public Double decodeWith(int idx, Object carrier) {
            if (carrier == null || idx < 0 || idx >= decoders.size()) return null;
            try {
                Object r = decoders.get(idx).invoke(carrier, 0.0);
                if (r instanceof Number n) {
                    double v = n.doubleValue();
                    return Double.isFinite(v) ? v : null;
                }
            } catch (Throwable ignored) {}
            return null;
        }

        /**
         * 造/写一个「读出来 == plain」的载体：先试会改动载体的入口（克隆体上两点标定），
         * 再试造对象型入口（造对象 → 读回 → 按比例修正）。全部试错都在克隆体或新对象上。
         */
        public WriteResult writeWith(int decoderIdx, double plain, Object current, Object template) {
            Object seed = current != null ? current : template;
            Port hint = lastGoodWriter;
            if (hint != null) {
                WriteResult r = tryPort(hint, decoderIdx, plain, current, seed);
                if (r.ok) return r;
                lastGoodWriter = null;
            }
            for (Port p : writers) {
                if (p == hint) continue;
                WriteResult r = tryPort(p, decoderIdx, plain, current, seed);
                if (r.ok) {
                    lastGoodWriter = p;
                    return r;
                }
            }
            return WriteResult.failed();
        }

        private WriteResult tryPort(Port p, int decoderIdx, double plain, Object current, Object seed) {
            if (p.touchesCarrier()) return tryInPlace(p, decoderIdx, plain, current, seed);
            return tryFactory(p, decoderIdx, plain);
        }

        /** 就地型：克隆体上两点标定（同一对象内线性关系精确），命中后写一次真实对象。 */
        private WriteResult tryInPlace(Port p, int decoderIdx, double plain, Object current, Object seed) {
            try {
                Object base = seed != null ? seed : allocate(carrierType);
                if (base == null) return WriteResult.failed();
                Object probe = cloneOf(base);
                if (probe == null) return WriteResult.failed();
                p.invoke(probe, 0.0);
                Double y0 = decodeWith(decoderIdx, probe);
                if (y0 == null) return WriteResult.failed();
                p.invoke(probe, 1.0);
                Double y1 = decodeWith(decoderIdx, probe);
                if (y1 == null) return WriteResult.failed();
                double a = y1 - y0;
                if (!Double.isFinite(a) || Math.abs(a) < 1e-9) return WriteResult.failed();
                double x = (plain - y0) / a;
                if (!Double.isFinite(x)) return WriteResult.failed();
                Object target = current != null ? current : allocate(carrierType);
                if (target == null) return WriteResult.failed();
                Object out = p.invoke(target, x);
                Object made = (out != null && carrierType.isInstance(out) && out != target) ? out : null;
                Object check = made != null ? made : target;
                Double got = decodeWith(decoderIdx, check);
                if (got != null && near(got, plain)) {
                    return made != null ? WriteResult.factory(made) : WriteResult.inPlace(target);
                }
            } catch (Throwable ignored) {}
            return WriteResult.failed();
        }

        /** 造对象型：造对象 → 用自己的读出口读回 → 按比例修正输入，直到读回值 == plain。 */
        private WriteResult tryFactory(Port p, int decoderIdx, double plain) {
            double x = plain;
            for (int i = 0; i < TRIAL_LIMIT; i++) {
                Object o;
                try {
                    o = p.invoke(null, x);
                } catch (Throwable t) {
                    o = null;
                }
                if (o == null || !carrierType.isInstance(o)) return WriteResult.failed();
                Double y = decodeWith(decoderIdx, o);
                if (y == null) return WriteResult.failed();
                if (near(y, plain)) return WriteResult.factory(o);
                if (Math.abs(x) > 1e-6 && Math.abs(y) > 1e-9) {
                    double a = y / x;
                    if (Double.isFinite(a) && Math.abs(a) > 1e-9) {
                        x = plain / a;
                        continue;
                    }
                }
                x = plain;
                if (i > 2) return WriteResult.failed();
            }
            return WriteResult.failed();
        }

        public String describe() {
            return "载体=" + carrierType.getSimpleName() + " 读入口=" + decoders.size()
                    + " 写入口=" + writers.size();
        }

        public String describeCandidates() {
            return "读=[" + signatures(decoders) + "] 写=[" + signatures(writers) + "]";
        }

        private String signatures(List<Port> ps) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < ps.size() && i < 10; i++) {
                if (i > 0) sb.append(", ");
                sb.append(ps.get(i).signature());
            }
            return sb.toString();
        }
    }

    /** 写入结果。 */
    public static final class WriteResult {
        public final boolean ok;
        public final boolean inPlace;
        public final Object carrier;

        private WriteResult(boolean ok, boolean inPlace, Object carrier) {
            this.ok = ok;
            this.inPlace = inPlace;
            this.carrier = carrier;
        }

        static WriteResult factory(Object o) { return new WriteResult(true, false, o); }
        static WriteResult inPlace(Object o) { return new WriteResult(true, true, o); }
        static WriteResult failed() { return new WriteResult(false, false, null); }
    }

    // ==================== 实体读写口 ====================

    /** 归属某类的实体写入口候选（参数 0 = 实体，其余数值）。 */
    public static List<Method> entityWritersOf(Class<?> owner) {
        Index idx = INDEX;
        if (idx == null || owner == null) return Collections.emptyList();
        List<Method> out = new ArrayList<>();
        for (Method m : idx.entityWrite) if (m.getDeclaringClass() == owner) out.add(m);
        return out;
    }

    /** 归属某类的实体读出口候选（参数 0 = 实体）。 */
    public static List<Method> entityReadersOf(Class<?> owner) {
        Index idx = INDEX;
        if (idx == null || owner == null) return Collections.emptyList();
        List<Method> out = new ArrayList<>();
        for (Method m : idx.entityRead) if (m.getDeclaringClass() == owner) out.add(m);
        return out;
    }

    /** 调用实体写口（参数 0 = 实体，其余数值位统一填 value）。 */
    public static boolean callEntityWriter(Method m, Entity entity, double value) {
        try {
            Class<?>[] ps = m.getParameterTypes();
            Object[] args = new Object[ps.length];
            args[0] = entity;
            for (int i = 1; i < ps.length; i++) args[i] = coerce(value, ps[i]);
            m.setAccessible(true);
            m.invoke(null, args);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 调用实体读口（参数 0 = 实体）。 */
    public static Double callEntityReader(Method m, Entity entity) {
        try {
            Class<?>[] ps = m.getParameterTypes();
            if (ps.length != 1) return null;
            m.setAccessible(true);
            Object r = m.invoke(null, entity);
            if (r instanceof Number n) {
                double v = n.doubleValue();
                return Double.isFinite(v) ? v : null;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    // ==================== 工具 ====================

    /** 从声明类的静态字段里找一个非空单例（实例方法的接收者 / 引用参数实参）。 */
    private static final Map<Class<?>, Object> SINGLETONS = new ConcurrentHashMap<>();
    private static final Object NO_SINGLETON = new Object();

    private static Object singletonOf(Class<?> declaring) {
        Object cached = SINGLETONS.get(declaring);
        if (cached == NO_SINGLETON) return null;
        if (cached != null) return cached;
        Object found = null;
        try {
            for (Field f : declaring.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers())) continue;
                if (!declaring.isAssignableFrom(f.getType())) continue;
                f.setAccessible(true);
                Object v = f.get(null);
                if (v != null) {
                    found = v;
                    break;
                }
            }
        } catch (Throwable ignored) {}
        SINGLETONS.put(declaring, found == null ? NO_SINGLETON : found);
        return found;
    }

    private static Object allocate(Class<?> c) {
        Unsafe u = UnsafeAccess.get();
        if (u == null) return null;
        try {
            return u.allocateInstance(c);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 克隆一个对象（只复制实例字段原值，不理解语义）：试错只在克隆体上进行。 */
    private static Object cloneOf(Object src) {
        if (src == null) return null;
        Object dst = allocate(src.getClass());
        Unsafe u = UnsafeAccess.get();
        if (dst == null || u == null) return null;
        try {
            for (Class<?> k = src.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
                for (Field f : k.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    long off = u.objectFieldOffset(f);
                    Class<?> t = f.getType();
                    if (t == int.class) u.putInt(dst, off, u.getInt(src, off));
                    else if (t == long.class) u.putLong(dst, off, u.getLong(src, off));
                    else if (t == float.class) u.putFloat(dst, off, u.getFloat(src, off));
                    else if (t == double.class) u.putDouble(dst, off, u.getDouble(src, off));
                    else if (t == short.class) u.putShort(dst, off, u.getShort(src, off));
                    else if (t == byte.class) u.putByte(dst, off, u.getByte(src, off));
                    else if (t == char.class) u.putChar(dst, off, u.getChar(src, off));
                    else if (t == boolean.class) u.putBoolean(dst, off, u.getBoolean(src, off));
                    else u.putObject(dst, off, u.getObject(src, off));
                }
            }
            return dst;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 数值近似（绝对容差按量级放大）。 */
    public static boolean near(double a, double b) {
        return Math.abs(a - b) <= Math.max(0.5, Math.max(Math.abs(a), Math.abs(b)) * 0.001);
    }

    private static Object coerce(double v, Class<?> type) {
        if (type == float.class) return (float) v;
        if (type == double.class) return v;
        if (type == long.class) return (long) Math.round(v);
        if (type == int.class) return (int) Math.round(v);
        if (type == short.class) return (short) Math.round(v);
        if (type == byte.class) return (byte) Math.round(v);
        if (type == char.class) return (char) Math.round(v);
        if (type == boolean.class) return v != 0;
        return v;
    }

    private static boolean numeric(Class<?> t) {
        return t == float.class || t == double.class || t == int.class || t == long.class
                || t == short.class || t == byte.class || t == char.class;
    }

    /** 参数位是否是「实体」型（Entity 本身或其实体子类；排除 Object 这种过宽的签名）。 */
    private static boolean entityParam(Class<?> p) {
        return p != null && p != Object.class
                && (p.isAssignableFrom(Entity.class) || Entity.class.isAssignableFrom(p));
    }

    /** 第三方引用类型（非 JDK / 非引擎 / 非自家）。 */
    private static boolean thirdParty(Class<?> t) {
        if (t == null || t.isPrimitive() || t.isArray() || t.isEnum() || t == String.class) return false;
        if (Number.class.isAssignableFrom(t) || CharSequence.class.isAssignableFrom(t)) return false;
        return interesting(t);
    }
}
