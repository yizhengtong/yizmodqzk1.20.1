package net.minecraft.client.yiz.tool.health;

import net.minecraft.client.yiz.core.asm.AgentBridge;
import net.minecraft.client.yiz.tool.key.UnsafeAccess;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 通用「外部静态单例藏血 Map」发现 + 篡改（S9 模式）。
 *
 * <p>部分模组把真实血量搬出实体本体，存进<b>静态 Map</b>（K=UUID/实体id/实体/弱引用，
 * V=数值或「含加密数值字段的容器对象」），并配一个每 tick 看门狗回读该 Map 拉回血量。
 * 现有 {@link HealthMapRegistry} 只认 {@code Map<K=Entity,V=Number>}，这里 K=UUID、V=对象 会漏。</p>
 *
 * <p>本类按纯类型签名 + 行为验证识别（不依赖任何类名/字段名/包名）：</p>
 * <ol>
 *   <li>枚举已加载类的<b>静态 Map 字段</b>，K ∈ {UUID, Integer, String, Entity, WeakReference}；</li>
 *   <li>按实体 UUID / id / 身份匹配出该实体的条目；</li>
 *   <li>V=数值 → 直读直写；V=对象 → 下钻找「密码载体」（含 char+float+int 三字段的对象），
 *       用 ARX 密码（reverse/rotate/subtract + 随机盐 + 类型标签）解码出「累计伤害」，
 *       真实血量 = 上限 − 累计伤害；</li>
 *   <li>写目标血量 = 反解累计伤害 → 编码回密码字段（Unsafe 写 final 字段）。</li>
 * </ol>
 *
 * <p>全程按「类型形状 + 行为验证」，可配置兜底，不引用任何目标模组类名。</p>
 */
public final class ExternalHealthStore {

    private static final org.slf4j.Logger LOGGER = net.minecraft.client.yiz.tizMod.LOGGER;
    private static final java.util.Set<String> LOGGED = ConcurrentHashMap.newKeySet();
    /** 写路径诊断：每类只打一次（避免刷屏）。 */
    private static final java.util.Set<String> WRITE_DIAG = ConcurrentHashMap.newKeySet();

    private ExternalHealthStore() {}

    // ==================== 公共 API ====================

    /** 从外部藏血 Map 读真实血量；非此类实体返回 null。 */
    public static Double readHealth(LivingEntity entity) {
        if (entity == null || entity.level().isClientSide()) return null;
        for (Map<?, ?> map : candidateMaps(entity)) {
            Object entry = matchEntry(map, entity);
            if (entry == null) continue;
            Double h = valueToHealth(entry, entity, map);
            if (h != null && Double.isFinite(h)) return h;
        }
        return null;
    }

    /** 写真实血量到外部藏血 Map；返回是否写入并命中（行为验证通过）。 */
    public static boolean writeHealth(LivingEntity entity, double target) {
        if (entity == null || entity.level().isClientSide()) return false;
        boolean any = false;
        for (Map<?, ?> map : candidateMaps(entity)) {
            Object entry = matchEntry(map, entity);
            if (entry == null) continue;
            if (writeEntryHealth(entry, entity, map, target)) any = true;
        }
        return any;
    }

    // ==================== 发现（静态 Map 字段） ====================

    /** 落盘缓存分域（发现结果由 {@link HealthDiscoveryCache} 持久化）。 */
    private static final String SECTION = "external_maps";

    /** 已发现的外部藏血 Map 缓存（静态单例，发现后基本不变）。 */
    private static volatile List<Map<?, ?>> MAP_CACHE = new ArrayList<>();
    /** 是否已完成一次性初始化（读盘反解 或 全类路径扫描一次）；置位后本轮不再枚举类路径。 */
    private static volatile boolean scanned = false;
    /** 已做过「按实体类就近发现」的类名（每类一次）。 */
    private static final java.util.Set<String> TARGETED = ConcurrentHashMap.newKeySet();
    /**
     * 已发现、但当前读不到值的候选字段（声明类还没 {@code <clinit>}、静态 Map 还没赋值等）。
     *
     * <p>旧实现靠「已加载类数量变化 → 全类路径重扫」兜住这种「晚出现」的 Map，代价是战斗中每 2 秒
     * 把整个类路径反射一遍。现在只补读这些字段句柄（Unsafe 读，几纳秒），不枚举任何类路径，
     * 成本随待补字段数线性且只减不增。</p>
     */
    private static final List<Field> PENDING_FIELDS = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** 懒加载 + 至多一次全类路径扫描：优先用落盘发现结果，缺失/全部失效才枚举类路径。 */
    private static List<Map<?, ?>> candidateMaps(LivingEntity entity) {
        ensureScanned();
        discoverForClass(entity == null ? null : entity.getClass());
        refreshPending();
        return MAP_CACHE;
    }

    /**
     * 一次性初始化：{@code isScanned} → 直接反解落盘句柄（零类枚举）；否则做<b>唯一一次</b>全类路径扫描。
     *
     * <p>旧实现按「已加载类数量变化」触发全类路径重扫（2 秒节流）→ 战斗中新类不断加载，
     * 等于每 2 秒把整个类路径反射一遍，首次攻击卡 1~3 秒。现在全局扫描只做一次且结果落盘。</p>
     */
    private static void ensureScanned() {
        if (scanned) return;
        synchronized (ExternalHealthStore.class) {
            if (scanned) return;
            if (HealthDiscoveryCache.isScanned(SECTION)) {
                List<Map<?, ?>> restored = restoreFromCache();
                if (!restored.isEmpty()) {
                    MAP_CACHE = restored;
                    scanned = true;
                    LOGGER.info("[HealthDiscovery] external_maps 缓存命中 {} 条，跳过全类路径扫描", restored.size());
                    return;
                }
                // 缓存一条都用不了（模组更新把类/字段移除、字段已不是 Map 等）→ 视为未扫描，退回一次性全扫
                LOGGER.info("[HealthDiscovery] external_maps 缓存 {} 条均不可用，回退一次性全类路径扫描",
                        HealthDiscoveryCache.get(SECTION).size());
            }
            int classes = scanMaps();
            scanned = classes > 0;   // 没有 agent / 类表不可用时不置位，留待下次重试（此时不枚举，成本为零）
        }
    }

    /** 从落盘缓存重建内存缓存：只反解字段句柄 + 读值，不枚举任何类。 */
    private static List<Map<?, ?>> restoreFromCache() {
        List<Map<?, ?>> out = new ArrayList<>();
        for (String[] pair : HealthDiscoveryCache.get(SECTION)) {
            try {
                Field f = HealthDiscoveryCache.resolve(pair[0], pair[1]);
                if (f == null || !isCandidateMapField(f)) continue;   // 类/字段已消失或类型已变
                Object v = readStatic(f);
                if (v instanceof Map<?, ?> map) out.add(map);
                else if (v == null) PENDING_FIELDS.add(f);            // 晚点补读
            } catch (Throwable ignored) {}
        }
        return out;
    }

    /** 按具体实体类就近发现（新加载的实体类不必等下一次全局扫描；只扫该类继承链，成本极低）。 */
    private static void discoverForClass(Class<?> entityClass) {
        if (entityClass == null || !TARGETED.add(entityClass.getName())) return;
        List<Map<?, ?>> found = new ArrayList<>();
        try {
            for (Class<?> c = entityClass; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    try {
                        if (!isCandidateMapField(f)) continue;
                        HealthDiscoveryCache.put(SECTION, c.getName(), f.getName());
                        Object v = readStatic(f);
                        if (v instanceof Map<?, ?> map) found.add(map);
                        else if (v == null) PENDING_FIELDS.add(f);
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
        mergeMaps(found);
    }

    /** 补读「已发现但读不到值」的字段（只读字段句柄，不枚举类路径）。 */
    private static void refreshPending() {
        if (PENDING_FIELDS.isEmpty()) return;
        List<Map<?, ?>> found = new ArrayList<>();
        for (Field f : PENDING_FIELDS) {
            try {
                Object v = readStatic(f);
                if (v instanceof Map<?, ?> map) {
                    found.add(map);
                    PENDING_FIELDS.remove(f);
                } else if (v != null) {
                    PENDING_FIELDS.remove(f);   // 值已不是 Map：字段语义变了，放弃
                }
            } catch (Throwable ignored) {
                PENDING_FIELDS.remove(f);
            }
        }
        mergeMaps(found);
    }

    /** 合并新发现的 Map 到缓存（按对象标识去重：第三方 Map 的 equals 可能有开销/副作用）。 */
    private static synchronized void mergeMaps(List<Map<?, ?>> extra) {
        if (extra.isEmpty()) return;
        List<Map<?, ?>> merged = new ArrayList<>(MAP_CACHE);
        for (Map<?, ?> m : extra) {
            boolean dup = false;
            for (Map<?, ?> e : merged) {
                if (e == m) {
                    dup = true;
                    break;
                }
            }
            if (!dup) merged.add(m);
        }
        MAP_CACHE = merged;
    }

    /**
     * 唯一一次全类路径扫描：枚举已加载类的静态 Map 字段（K ∈ 实体身份型）→ 读值 → 命中即落盘。
     *
     * @return 已加载类数量；{@code <0} 表示类表不可用（没有 agent），调用方不置位 {@code scanned} 以便下次重试
     */
    private static int scanMaps() {
        Class<?>[] all = allLoadedClasses();
        if (all == null || all.length == 0) return -1;
        List<Map<?, ?>> fresh = new ArrayList<>();
        int hits = 0;
        LOGGER.info("[ExtStore] external_maps 首次全类路径扫描开始（仅此一次，结果落盘）");
        long t0 = System.currentTimeMillis();
        for (Class<?> clazz : all) {
            try {
                for (Field f : clazz.getDeclaredFields()) {
                    if (!isCandidateMapField(f)) continue;
                    HealthDiscoveryCache.put(SECTION, f.getDeclaringClass().getName(), f.getName());
                    try {
                        Object v = readStatic(f);
                        if (v instanceof Map<?, ?> map) {
                            fresh.add(map);
                            hits++;
                        } else if (v == null) {
                            PENDING_FIELDS.add(f);   // 声明类还没 <clinit> 等，晚点补读
                        }
                    } catch (Throwable ignored) {}
                }
            } catch (Throwable ignored) {}
        }
        if (!fresh.isEmpty()) MAP_CACHE = fresh;   // 原子交换，避免清空/重填期间读竞态
        HealthDiscoveryCache.markScanned(SECTION);   // 全局扫描只做这一次，结果落盘
        LOGGER.info("[ExtStore] external_maps 全类路径扫描完成：命中 {} 个藏血 Map（{} 类，耗时 {} ms）",
                hits, all.length, System.currentTimeMillis() - t0);
        return all.length;
    }

    /** 全类路径扫描与「就近发现」共用的字段判据：静态 + 非合成 + Map 类型 + K 为实体身份型。 */
    private static boolean isCandidateMapField(Field f) {
        int m = f.getModifiers();
        if (f.isSynthetic() || !Modifier.isStatic(m)) return false;
        if (!Map.class.isAssignableFrom(f.getType())) return false;
        return isEntityKey(f.getGenericType());
    }

    /**
     * 读静态字段值；返回 {@code null} 表示声明类还没初始化、字段仍是默认值。
     *
     * <p>用 Unsafe 读静态字段，避免 {@code Field.get(null)} 触发声明类 <clinit>：
     * 反射 get 会 ensureClassInitialized，把 Registrate 等第三方库的 <clinit> 引爆
     * （其 <clinit> 里 ObfuscationReflectionHelper 找 LootContextParamSets.REGISTRY 失败
     * → NoSuchFieldException / NoClassDefFoundError）。Unsafe 读绕过类初始化。</p>
     */
    private static Object readStatic(Field f) throws Throwable {
        f.setAccessible(true);
        sun.misc.Unsafe u = UnsafeAccess.get();
        if (u != null) {
            Object base = u.staticFieldBase(f);
            long offset = u.staticFieldOffset(f);
            return u.getObject(base, offset);
        }
        return f.get(null);
    }

    /** K 是否为「实体身份」型（UUID / 实体id / 实体 / 弱引用）。 */
    private static boolean isEntityKey(Type genericType) {
        if (!(genericType instanceof ParameterizedType pt)) return false;
        Type[] args = pt.getActualTypeArguments();
        if (args == null || args.length != 2) return false;
        Type kt = args[0];
        if (kt instanceof Class<?> c) {
            return c == java.util.UUID.class
                    || c == Integer.class || c == int.class
                    || c == String.class
                    || Entity.class.isAssignableFrom(c)
                    || c == java.lang.ref.WeakReference.class;
        }
        return false;
    }

    private static Class<?>[] allLoadedClasses() {
        try {
            var inst = AgentBridge.getInstrumentation();
            if (inst != null) return inst.getAllLoadedClasses();
        } catch (Throwable ignored) {}
        return null;
    }

    // ==================== 条目匹配 ====================

    private static Object matchEntry(Map<?, ?> map, LivingEntity entity) {
        try {
            Object byUuid = map.get(entity.getUUID());
            if (byUuid != null) return byUuid;
        } catch (Throwable ignored) {}
        try {
            Object byId = map.get(entity.getId());
            if (byId != null) return byId;
        } catch (Throwable ignored) {}
        try {
            Object byEntity = map.get(entity);
            if (byEntity != null) return byEntity;
        } catch (Throwable ignored) {}
        try {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                Object k = e.getKey();
                if (k instanceof java.lang.ref.WeakReference<?> wr && wr.get() == entity) return e.getValue();
            }
        } catch (Throwable ignored) {}
        return null;
    }

    // ==================== 读：条目 → 血量 ====================

    private static Double valueToHealth(Object entry, LivingEntity entity, Map<?, ?> map) {
        // V=数值：直接当血量（方向按 getHealth 近似判断，不区分逆序，交给调用方用行为验证兜底）
        if (entry instanceof Number n) {
            // 直接取 double：值可能是 Long（量级到 1e12），经 float 中转会丢精度
            return n.doubleValue();
        }
        // V=对象：下钻找密码载体，解码累计伤害，health = max − acc
        CipherCarrier cc = findCipherCarrier(entry);
        if (cc == null) return null;
        float acc = decodeCipher(cc);
        float max = bossMaxOf(entry, entity);
        if (!Float.isFinite(max) || max <= 0) return null;
        double health = (double) max - (double) acc;
        // 行为验证：与 getHealth 近似才信任（防误认无关对象）
        try {
            double gh = entity.getHealth();
            if (Math.abs(health - gh) > Math.max(0.5, Math.max(Math.abs(health), Math.abs(gh)) * 0.001)) {
                return null;
            }
        } catch (Throwable ignored) {
            return null;
        }
        logOnce(entity, map, cc);
        return Math.max(0.0, health);
    }

    private static float bossMaxOf(Object entry, LivingEntity entity) {
        // 优先条目内 ≈ getMaxHealth() 的 float 字段；失败回退 entity.getMaxHealth()
        try {
            float gmh = entity.getMaxHealth();
            if (Float.isFinite(gmh) && gmh > 0) {
                for (Field f : allInstanceFields(entry.getClass())) {
                    if (f.getType() != float.class) continue;
                    try {
                        f.setAccessible(true);
                        float v = f.getFloat(entry);
                        if (Math.abs(v - gmh) <= Math.max(0.5, gmh * 0.001)) return v;
                    } catch (Throwable ignored) {}
                }
                return gmh;
            }
        } catch (Throwable ignored) {}
        return Float.NaN;
    }

    // ==================== 写：条目 → 目标血量 ====================

    private static boolean writeEntryHealth(Object entry, LivingEntity entity, Map<?, ?> map, double target) {
        if (entry instanceof Number) {
            // 直接写数值条目（用 Unsafe/反射写 Map 值；键为 UUID/id，沿用 putUnchecked 思路）
            return putNumber(map, entity, target);
        }
        float max = bossMaxOf(entry, entity);
        if (!Float.isFinite(max) || max <= 0) return false;
        float acc = (float) Math.max(0.0, (double) max - target);
        // 写所有密码字段（多个密码字段都可能被权威程序读，字段序不可靠，全部同步写）
        boolean any = false;
        List<Field> fields = cipherFields(entry);
        for (Field f : fields) {
            if (writeCipherField(entry, f, acc)) any = true;
        }
        // 诊断：写后回读解码值，判断写入是否生效（限频：每类只打前若干条）
        if (WRITE_DIAG.add(entity.getClass().getName())) {
            double rb = Double.NaN;
            CipherCarrier cc = findCipherCarrier(entry);
            if (cc != null) rb = (double) max - decodeCipher(cc);
            LOGGER.warn("[ExtWrite] {} max={} acc={} 密码字段数={} any={} 回读血={}",
                entity.getClass().getSimpleName(), max, acc, fields.size(), any, rb);
        }
        return any;
    }

    /** 直接写 K=UUID/id 的数值 Map 条目（unreflectSpecial 锁基类 put 绕过鉴权）。 */
    private static boolean putNumber(Map<?, ?> map, LivingEntity entity, double value) {
        try {
            Object key = null;
            try {
                key = map.containsKey(entity.getUUID()) ? entity.getUUID()
                        : (map.containsKey(entity.getId()) ? entity.getId() : entity);
            } catch (Throwable ignored) {}
            if (key == null) key = entity;
            // 直接反射 put（对 ConcurrentHashMap 无鉴权重写，简单可靠）
            java.lang.reflect.Method put = map.getClass().getMethod("put", Object.class, Object.class);
            put.invoke(map, key, boxLikeExisting(map, key, value));
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 按 Map 中原值的<b>装箱类型</b>写回，不要一律写 Float。
     *
     * <p>数值条目只保证是 {@link Number}，实际可能是 Long/Integer/Double。无条件写 Float 会让
     * 持有方下次按自己的类型取值时 {@code ClassCastException} —— 崩的是对方的 tick 循环，
     * 崩溃栈里看不到本模组任何一帧。</p>
     */
    private static Object boxLikeExisting(Map<?, ?> map, Object key, double value) {
        Object old;
        try {
            old = map.get(key);
        } catch (Throwable ignored) {
            return (float) value;
        }
        if (old instanceof Long) return Math.round(value);
        if (old instanceof Integer) return (int) value;
        if (old instanceof Double) return value;
        if (old instanceof Short) return (short) value;
        if (old instanceof Byte) return (byte) value;
        return (float) value;
    }

    // ==================== 密码载体下钻 ====================

    /** 密码载体：char 标签 + float 密文 + int 盐 三字段对象。 */
    private static final class CipherCarrier {
        Object cipherObj;
        Field cipherField;   // float 密文字段
        char tag;
        int salt;
    }

    private static CipherCarrier findCipherCarrier(Object entry) {
        for (Field f : allInstanceFields(entry.getClass())) {
            Class<?> t = f.getType();
            if (t.isPrimitive() || t == String.class || t.isEnum()) continue;
            if (t.getName().startsWith("java.") || t.getName().startsWith("net.minecraft.")) continue;
            Object inner;
            try {
                f.setAccessible(true);
                inner = f.get(entry);
            } catch (Throwable ignored) {
                continue;
            }
            if (inner == null || inner == entry) continue;
            CipherCarrier cc = asCipherCarrier(inner);
            if (cc != null) return cc;
        }
        return null;
    }

    private static CipherCarrier asCipherCarrier(Object obj) {
        Field charF = null, floatF = null, intF = null;
        for (Field f : allInstanceFields(obj.getClass())) {
            Class<?> t = f.getType();
            if (t == char.class && charF == null) charF = f;
            else if (t == float.class && floatF == null) floatF = f;
            else if (t == int.class && intF == null) intF = f;
        }
        if (charF == null || floatF == null || intF == null) return null;
        try {
            charF.setAccessible(true);
            floatF.setAccessible(true);
            intF.setAccessible(true);
            CipherCarrier cc = new CipherCarrier();
            cc.cipherObj = obj;
            cc.cipherField = floatF;
            cc.tag = charF.getChar(obj);
            cc.salt = intF.getInt(obj);
            return cc;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 条目上所有「密码字段」（类型是 char+float+int 三字段的密码类），无论当前值是否 null。 */
    private static List<Field> cipherFields(Object entry) {
        List<Field> out = new ArrayList<>();
        for (Field f : allInstanceFields(entry.getClass())) {
            Class<?> t = f.getType();
            if (t.isPrimitive() || t == String.class || t.isEnum()) continue;
            if (t.getName().startsWith("java.") || t.getName().startsWith("net.minecraft.")) continue;
            if (isCipherClass(t)) out.add(f);
        }
        return out;
    }

    private static boolean isCipherClass(Class<?> c) {
        boolean charF = false, floatF = false, intF = false;
        for (Field f : allInstanceFields(c)) {
            Class<?> t = f.getType();
            if (t == char.class) charF = true;
            else if (t == float.class) floatF = true;
            else if (t == int.class) intF = true;
        }
        return charF && floatF && intF;
    }

    /** 写单个密码字段：已有密码对象则改写密文（保持标签/盐），null 则分配新对象。 */
    private static boolean writeCipherField(Object entry, Field field, float acc) {
        try {
            field.setAccessible(true);
            Object existing = field.get(entry);
            if (existing != null) {
                CipherCarrier cc = asCipherCarrier(existing);
                if (cc == null) return false;
                return unsafeWriteFloat(cc.cipherObj, cc.cipherField, encodeCipher(cc.tag, cc.salt, acc));
            }
            return allocateAndWriteField(entry, field, acc);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 分配一个新的密码对象（Unsafe.allocateInstance 绕过构造）并以 tag='T'/salt=0 写入累计伤害。 */
    private static boolean allocateAndWriteField(Object entry, Field cipherField, float acc) {
        try {
            Class<?> cipherClass = cipherField.getType();
            Field charF = null, floatF = null, intF = null;
            for (Field f : allInstanceFields(cipherClass)) {
                Class<?> t = f.getType();
                if (t == char.class && charF == null) charF = f;
                else if (t == float.class && floatF == null) floatF = f;
                else if (t == int.class && intF == null) intF = f;
            }
            if (charF == null || floatF == null || intF == null) return false;
            Unsafe u = UnsafeAccess.get();
            if (u == null) return false;
            Object inst = u.allocateInstance(cipherClass);
            u.putChar(inst, u.objectFieldOffset(charF), 'T');                 // tag='T'（恒等）
            u.putInt(inst, u.objectFieldOffset(intF), 0);                     // salt=0
            u.putFloat(inst, u.objectFieldOffset(floatF), encodeCipher('T', 0, acc));
            u.putObject(entry, u.objectFieldOffset(cipherField), inst);       // 挂回条目字段
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ==================== ARX 密码（reverse/rotate/subtract + 盐 + 类型标签） ====================

    // 轮常数必须用 +1640531527（0x61C88647，-0x9E3779B9 的补码），用负数会与目标密码互逆反解失败。
    private static final int ROUND = 1640531527;

    private static int fwd(int x) {
        x = Integer.reverse(x);
        x -= ROUND;
        return Integer.rotateLeft(x, 17);
    }

    private static int inv(int x) {
        x = Integer.rotateRight(x, 17);
        x += ROUND;
        return Integer.reverse(x);
    }

    private static float applyTagDecode(char tag, float v) {
        switch (tag) {
            case 'B': return v * -1.0F;
            case 'M': return v * 10.0F;
            case 'T': return v;
            default: return Float.NaN;
        }
    }

    private static float applyTagEncode(char tag, float v) {
        switch (tag) {
            case 'B': return v * -1.0F;
            case 'M': return v * 0.1F;
            case 'T': return v;
            default: return Float.NaN;
        }
    }

    /** 解码累计伤害：acc = tagDecode(intBitsToFloat(inv(floatBits(cipher) ^ salt)))。 */
    private static float decodeCipher(CipherCarrier cc) {
        try {
            float cipher;
            cc.cipherField.setAccessible(true);
            cipher = cc.cipherField.getFloat(cc.cipherObj);
            int bits = Float.floatToRawIntBits(cipher) ^ cc.salt;
            float v = Float.intBitsToFloat(inv(bits));
            float acc = applyTagDecode(cc.tag, v);
            if (!Float.isFinite(acc)) acc = 0.0F;
            return acc;
        } catch (Throwable t) {
            return 0.0F;
        }
    }

    /** 编码累计伤害为密文 float（保持原标签与盐）。 */
    private static float encodeCipher(char tag, int salt, float acc) {
        float v = applyTagEncode(tag, acc);
        if (!Float.isFinite(v)) v = acc;
        int bits = fwd(Float.floatToRawIntBits(v)) ^ salt;
        return Float.intBitsToFloat(bits);
    }

    // ==================== 工具 ====================

    /** Unsafe 写 final float 字段（密文字段常为 final）。 */
    private static boolean unsafeWriteFloat(Object obj, Field f, float value) {
        try {
            Unsafe u = UnsafeAccess.get();
            if (u == null) {
                f.setAccessible(true);
                f.setFloat(obj, value);   // 非 final 时反射兜底
                return true;
            }
            long off = u.objectFieldOffset(f);
            u.putFloat(obj, off, value);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static List<Field> allInstanceFields(Class<?> clazz) {
        List<Field> list = new ArrayList<>();
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                list.add(f);
            }
        }
        return list;
    }

    private static void logOnce(LivingEntity entity, Map<?, ?> map, CipherCarrier cc) {
        String key = entity.getClass().getName();
        if (LOGGED.add(key)) {
            LOGGER.info("[ExtStore] {} 命中外部藏血 Map {} → 密码载体 {}", key, map.getClass().getSimpleName(),
                    cc.cipherObj.getClass().getName());
        }
    }
}
