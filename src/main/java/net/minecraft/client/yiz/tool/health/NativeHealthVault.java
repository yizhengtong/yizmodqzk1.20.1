package net.minecraft.client.yiz.tool.health;

import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.util.UUID;

/**
 * 真实生命值 native 堆外权威存储（fantasy Unsafe 清零对抗）。
 *
 * <p><b>为什么用 native</b>：外部用 Unsafe/IMPL_LOOKUP 直改 Java 对象内存（DataItem.value、
 * ConcurrentHashMap 内部 Node、实例 float 字段），能绕过一切 Java override/mixin/写鉴权/agent 覆盖，
 * 一次性同步清零多个 Java 存储层。而 <b>Unsafe.allocateMemory 分配的堆外 native 内存不是 Java 对象</b>——
 * 全堆扫描/对象图遍历物理上到不了 native 堆，外部要改必须知道具体 native 地址。地址只存于本类持有，
 * 外部不知道分配地址就无从下手。</p>
 *
 * <p><b>结构</b>：native 内存做开放寻址哈希表，UUID → {@code float} 真值。每槽定长：</p>
 * <pre>
 *   [0..7]  = UUID 高位 long
 *   [8..15] = UUID 低位 long
 *   [16..19]= float 真值
 *   [20..23]= int 校验（value 原始位 ^ 每进程随机种子）
 * </pre>
 * 空槽 UUID==0。读槽：校验失败（外部若侥幸猜中地址裸写）→ 返回 NaN 触发上层从镜像恢复。
 * 写入原子：先写校验位再写值（读侧校验=值&校验一致才采纳），防半写状态。
 *
 * <p><b>权威语义</b>：本类是服务端逻辑血量唯一权威。Java 镜像层（AUTHORITY_TABLE / 容器 /
 * 混淆串）被外部清零后，每 tick {@code enforceFromNative} 从本表恢复镜像；对外读（getHealth）
 * 也直接读本表（表/容器被改不影响）。客户端实体无 native 槽（仅服务端 allocate），客户端仍读
 * 串/DATA_HEALTH 镜像。</p>
 *
 * <p><b>生命周期</b>：实体首个服务端 tick 注册时 {@link #allocate}；死亡/卸载 {@link #release}；
 * 服务器停止 {@link #freeAll}（防止 native 泄漏）。崩溃丢 native → 存档/复活经混淆串重建灌回。</p>
 *
 * <p><b>校验种子</b>：每进程 {@code SecureRandom} 生成，存 native 首块（{@code SEED_OFFSET}），
 * 仅本类读取；外部即使读到槽也不易还原有效校验。</p>
 */
public final class NativeHealthVault {

    private static final Unsafe U;

    // ---- native 内存布局 ----
    /** 头区：校验种子（8B）占 native 首块。 */
    private static final long SEED_OFFSET = 0;
    private static final int HEAD_SIZE = 8;
    /** 每槽字节数：uuid高8 + uuid低8 + value4 + checksum4。 */
    private static final int SLOT_SIZE = 24;
    /** value 相对槽内偏移。 */
    private static final int OFF_UUID_HI = 0;
    private static final int OFF_UUID_LO = 8;
    private static final int OFF_VALUE = 16;
    private static final int OFF_CHECK = 20;

    private static final int INITIAL_CAPACITY = 512;
    private static final double MAX_LOAD = 0.5;

    static {
        Unsafe u;
        try {
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            u = (Unsafe) f.get(null);
        } catch (Throwable t) {
            u = null;
        }
        U = u;
    }

    /** native 表头基地址（含校验种子 + 槽数组）；0 = 未初始化。 */
    private static long tableBase = 0L;
    private static int capacity = 0;
    private static int size = 0;
    private static volatile long seed;

    /** 诊断限频：元数据被外部改 / 表重建 / 命中槽校验失败。 */
    private static final java.util.concurrent.atomic.AtomicInteger META_LOG = new java.util.concurrent.atomic.AtomicInteger();
    private static void logMeta(String what, long base, int cap, long sd) {
        if (META_LOG.incrementAndGet() > 30) return;
        StringBuilder sb = new StringBuilder();
        StackTraceElement[] st = Thread.currentThread().getStackTrace();
        for (int i = 3; i < Math.min(st.length, 12); i++) sb.append("\n    ").append(st[i]);
        net.minecraft.client.yiz.tizMod.LOGGER.warn(
            "[NativeHealthVault] {} (base=0x{} cap={} size={} seed=0x{} 线程={}):{}",
            what, Long.toHexString(base), cap, size, Long.toHexString(sd), Thread.currentThread().getName(), sb);
    }

    private NativeHealthVault() {}

    // ==================== 初始化 / 全清 ====================

    /** 懒初始化（服务端首实体注册时）。返回是否可用。 */
    public static boolean ensureInit() {
        if (U == null) return false;
        if (tableBase != 0L) return true;
        synchronized (NativeHealthVault.class) {
            if (tableBase != 0L) return true;
            try {
                seed = new java.security.SecureRandom().nextLong();
                long base = U.allocateMemory(HEAD_SIZE + (long) INITIAL_CAPACITY * SLOT_SIZE);
                U.putLong(base + SEED_OFFSET, seed);
                // 清零全部槽（UUID=0 空）
                for (int i = 0; i < INITIAL_CAPACITY * SLOT_SIZE; i += 8) {
                    U.putLong(base + HEAD_SIZE + i, 0L);
                }
                tableBase = base;
                capacity = INITIAL_CAPACITY;
                size = 0;
                logMeta("表初始化", base, capacity, seed);
                return true;
            } catch (Throwable t) {
                net.minecraft.client.yiz.tizMod.LOGGER.error("[NativeHealthVault] 初始化失败", t);
                return false;
            }
        }
    }

    /** 释放全部 native 内存（服务器停止/世界卸载）。 */
    public static void freeAll() {
        synchronized (NativeHealthVault.class) {
            if (tableBase != 0L) {
                try {
                    U.freeMemory(tableBase);
                } catch (Throwable ignored) {}
                tableBase = 0L;
                capacity = 0;
                size = 0;
            }
        }
    }

    // ==================== 槽寻址 ====================

    private static long slotBase(int index) {
        return tableBase + HEAD_SIZE + (long) index * SLOT_SIZE;
    }

    /** 槽是否为空（UUID 全 0）。 */
    private static boolean isSlotEmpty(long slot) {
        return U.getLong(slot + OFF_UUID_HI) == 0L && U.getLong(slot + OFF_UUID_LO) == 0L;
    }

    /** 槽是否命中给定 UUID。 */
    private static boolean isSlotMatch(long slot, long hi, long lo) {
        return U.getLong(slot + OFF_UUID_HI) == hi && U.getLong(slot + OFF_UUID_LO) == lo;
    }

    private static long hash(long hi, long lo) {
        long h = hi * 0x9E3779B97F4A7C15L ^ lo;
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        return Math.floorMod(h, capacity);
    }

    // ==================== 读 ====================

    /** 读实体真值；无槽/校验失败返回 NaN（上层回退镜像）。 */
    public static float get(UUID uuid) {
        if (uuid == null) return Float.NaN;
        long hi = uuid.getMostSignificantBits();
        long lo = uuid.getLeastSignificantBits();
        if (tableBase == 0L) {
            logMeta("get-表未初始化", 0L, 0, seed);
            return Float.NaN;
        }
        int idx = (int) hash(hi, lo);
        for (int probe = 0; probe < capacity; probe++) {
            long slot = slotBase(idx);
            if (isSlotEmpty(slot)) {
                logMeta("get-槽不存在(空)", tableBase, capacity, seed);
                return Float.NaN;
            }
            if (isSlotMatch(slot, hi, lo)) {
                float v = U.getFloat(slot + OFF_VALUE);
                int check = U.getInt(slot + OFF_CHECK);
                long s = seed;
                // 校验：value 原始位 ^ seed ^ uuid低位 == check 才采纳（防地址猜中裸写值）
                if ((Float.floatToRawIntBits(v) ^ (int) s ^ (int) lo) == check) {
                    if (v == 0.0f) logMeta("get-命中槽值为0", tableBase, capacity, seed);
                    return v;
                }
                logMeta("get-命中槽校验失败", tableBase, capacity, seed);
                return Float.NaN; // 校验失败 → 视为被污染
            }
            idx = (idx + 1) % capacity;
        }
        logMeta("get-寻址未命中(槽满)", tableBase, capacity, seed);
        return Float.NaN;
    }

    // ==================== 写 ====================

    /** 写实体真值；不存在则插入（若槽满先扩容）。返回是否成功。 */
    public static boolean put(UUID uuid, float value) {
        if (uuid == null || U == null) return false;
        if (!ensureInit()) return false;
        long hi = uuid.getMostSignificantBits();
        long lo = uuid.getLeastSignificantBits();
        // 已存在 → 直接更新（先值后校验？为防并发半读，先写校验后写值）
        if (updateExisting(hi, lo, value)) return true;
        if ((double) (size + 1) / capacity > MAX_LOAD) {
            if (!resize()) return false;
        }
        int idx = (int) hash(hi, lo);
        for (int probe = 0; probe < capacity; probe++) {
            long slot = slotBase(idx);
            if (isSlotEmpty(slot)) {
                // 先放 uuid，再放校验，最后放值——读侧先见 uuid 匹配后校验值一致性
                U.putLong(slot + OFF_UUID_HI, hi);
                U.putLong(slot + OFF_UUID_LO, lo);
                int check = Float.floatToRawIntBits(value) ^ (int) seed ^ (int) lo;
                U.putInt(slot + OFF_CHECK, check);
                U.putFloat(slot + OFF_VALUE, value);
                size++;
                return true;
            }
            idx = (idx + 1) % capacity;
        }
        return false;
    }

    private static boolean updateExisting(long hi, long lo, float value) {
        int idx = (int) hash(hi, lo);
        for (int probe = 0; probe < capacity; probe++) {
            long slot = slotBase(idx);
            if (isSlotEmpty(slot)) return false;
            if (isSlotMatch(slot, hi, lo)) {
                int check = Float.floatToRawIntBits(value) ^ (int) seed ^ (int) lo;
                U.putInt(slot + OFF_CHECK, check);
                U.putFloat(slot + OFF_VALUE, value);
                return true;
            }
            idx = (idx + 1) % capacity;
        }
        return false;
    }

    /** 删除槽（死亡/卸载）。 */
    public static boolean remove(UUID uuid) {
        if (uuid == null || tableBase == 0L) return false;
        long hi = uuid.getMostSignificantBits();
        long lo = uuid.getLeastSignificantBits();
        int idx = (int) hash(hi, lo);
        for (int probe = 0; probe < capacity; probe++) {
            long slot = slotBase(idx);
            if (isSlotEmpty(slot)) return false;
            if (isSlotMatch(slot, hi, lo)) {
                U.putLong(slot + OFF_UUID_HI, 0L);
                U.putLong(slot + OFF_UUID_LO, 0L);
                size--;
                return true;
            }
            idx = (idx + 1) % capacity;
        }
        return false;
    }

    // ==================== 扩容 ====================

    private static boolean resize() {
        int oldCap = capacity;
        long oldBase = tableBase;
        int newCap = oldCap * 2;
        try {
            long newBase = U.allocateMemory(HEAD_SIZE + (long) newCap * SLOT_SIZE);
            U.putLong(newBase + SEED_OFFSET, seed);
            for (int i = 0; i < newCap * SLOT_SIZE; i += 8) {
                U.putLong(newBase + HEAD_SIZE + i, 0L);
            }
            synchronized (NativeHealthVault.class) {
                tableBase = newBase;
                capacity = newCap;
                size = 0;
            }
            // 旧表逐槽迁移
            for (int i = 0; i < oldCap; i++) {
                long oldSlot = oldBase + HEAD_SIZE + (long) i * SLOT_SIZE;
                long hi = U.getLong(oldSlot + OFF_UUID_HI);
                long lo = U.getLong(oldSlot + OFF_UUID_LO);
                if (hi == 0L && lo == 0L) continue;
                float v = U.getFloat(oldSlot + OFF_VALUE);
                insertRehash(hi, lo, v);
            }
            U.freeMemory(oldBase);
            return true;
        } catch (Throwable t) {
            net.minecraft.client.yiz.tizMod.LOGGER.error("[NativeHealthVault] 扩容失败", t);
            return false;
        }
    }

    private static void insertRehash(long hi, long lo, float value) {
        int idx = (int) hash(hi, lo);
        for (int probe = 0; probe < capacity; probe++) {
            long slot = slotBase(idx);
            if (isSlotEmpty(slot)) {
                U.putLong(slot + OFF_UUID_HI, hi);
                U.putLong(slot + OFF_UUID_LO, lo);
                int check = Float.floatToRawIntBits(value) ^ (int) seed ^ (int) lo;
                U.putInt(slot + OFF_CHECK, check);
                U.putFloat(slot + OFF_VALUE, value);
                size++;
                return;
            }
            idx = (idx + 1) % capacity;
        }
    }

    // ==================== 状态 ====================

    public static int size() { return size; }
    public static int capacity() { return capacity; }
}
