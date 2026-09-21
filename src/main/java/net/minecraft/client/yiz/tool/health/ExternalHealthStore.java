package net.minecraft.client.yiz.tool.health;

import net.minecraft.client.yiz.tool.key.FieldHandle;
import net.minecraft.client.yiz.tool.key.UnsafeAccess;
import net.minecraft.world.entity.LivingEntity;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
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

    /**
     * 候选外部藏血 Map：字段判据（静态 + {@code Map} + K 是实体身份型）
     * 由 {@link HealthDiscovery} 全类路径枚举一次并缓存<b>字段句柄</b>；这里每次调用都
     * <b>现读字段值</b>（Unsafe 静态读，不触发声明类 {@code <clinit>}），拿到当前真正挂在字段上的 Map。
     *
     * <p>不再自己枚举类表，也不缓存解析出来的 Map 实例：旧实现把「字段 → Map 实例」整体缓存，
     * 第三方把字段换成新 Map 后会一直读旧实例 → 读不到真血（判定退回被 delta 抬高的显示值）。
     * 现在缓存的只有字段句柄，实例每次都重新取。</p>
     */
    private static List<Map<?, ?>> candidateMaps(LivingEntity entity) {
        HealthDiscovery.Snapshot snap = HealthDiscovery.current();
        if (snap == null) return java.util.Collections.emptyList();
        List<Map<?, ?>> out = new ArrayList<>();
        for (FieldHandle h : snap.externalKeyMaps()) {
            Object v = h.tryGetObject();
            if (v instanceof Map<?, ?> map) out.add(map);
        }
        return out;
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

    /**
     * 实例字段清单按类缓存（<b>只缓存字段句柄</b>，值每次现读）。
     *
     * <p>攻击路径上 {@code bossMaxOf/findCipherCarrier/cipherFields/isCipherClass} 都会遍历条目类的
     * 实例字段；旧实现每次都 {@code getDeclaredFields()} 走一遍继承链并 {@code setAccessible}，
     * 是无槽实体每次攻击都要付的重复成本。字段集合不会变，故按类缓存。</p>
     */
    private static final Map<Class<?>, List<Field>> INSTANCE_FIELDS = new ConcurrentHashMap<>();

    private static List<Field> allInstanceFields(Class<?> clazz) {
        return INSTANCE_FIELDS.computeIfAbsent(clazz, c -> {
            List<Field> list = new ArrayList<>();
            for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
                for (Field f : k.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    try {
                        f.setAccessible(true);
                    } catch (Throwable ignored) {}
                    list.add(f);
                }
            }
            return List.copyOf(list);
        });
    }

    private static void logOnce(LivingEntity entity, Map<?, ?> map, CipherCarrier cc) {
        String key = entity.getClass().getName();
        if (LOGGED.add(key)) {
            LOGGER.info("[ExtStore] {} 命中外部藏血 Map {} → 密码载体 {}", key, map.getClass().getSimpleName(),
                    cc.cipherObj.getClass().getName());
        }
    }
}
