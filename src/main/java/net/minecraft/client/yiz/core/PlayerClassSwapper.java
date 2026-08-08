package net.minecraft.client.yiz.core;

import net.minecraft.world.entity.player.Player;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 通过 {@code sun.misc.Unsafe} 在运行时替换单个 Player 实例的 class 指针，
 * 将其重定向到 {@code ProtectedServerPlayer}，实现实例级别的完全保护。
 *
 * <p>与 Mixin / Agent 的本质区别：
 * <ul>
 *   <li>Mixin/Agent 修改的是 class 的字节码 → 所有实例共享</li>
 *   <li>本类替换的是对象头中的 klass 指针 → 仅影响指定实例</li>
 *   <li>其他模组的 Mixin 注入在 Player.class 上，不影响 ProtectedServerPlayer</li>
 * </ul>
 *
 * <p>安全警告：此操作是 JVM 内部行为，理论上不保证跨 JVM 版本兼容。
 * 当前实现针对 HotSpot 64-bit (Java 17)。</p>
 *
 * <p>1.20.1 移植说明：{@code ProtectedServerPlayer} 尚未移植，{@link #isProtected(Player)}
 * 暂用类名字符串比对（行为等价），待 ProtectedServerPlayer 移植后恢复直接类引用。</p>
 */
public final class PlayerClassSwapper {

    /** 保护类全限定名（ProtectedServerPlayer 移植前用作 isProtected 的字符串比对依据）。 */
    private static final String PROTECTED_CLASS_NAME = "net.minecraft.client.yiz.core.ProtectedServerPlayer";

    private static final Unsafe U;
    private static final long KLASS_OFFSET;

    /** 原始 Class → 保护 Class 的映射（用于 restore） */
    private static final ConcurrentHashMap<Class<?>, Class<?>> SWAP_MAP = new ConcurrentHashMap<>();

    /** 玩家 UUID → 原始 klass 地址（用于 restore） */
    private static final ConcurrentHashMap<String, Long> ORIGINAL_KLASS = new ConcurrentHashMap<>();

    static {
        U = getUnsafe();
        KLASS_OFFSET = determineKlassOffset();
    }

    /** 保护态玩家 UUID 集合（供 ASM Agent 注入代码查询） */
    private static final Set<String> PROTECTED_UUIDS = ConcurrentHashMap.newKeySet();

    private PlayerClassSwapper() {}

    /**
     * 供 ASM Agent 注入的 die() / remove() 钩子查询。
     * 此方法在字节码注入层被调用，Agent 层早于所有 Mixin，不可覆盖。
     */
    @SuppressWarnings("unused")
    public static boolean isProtectedByUuid(String uuidStr) {
        return PROTECTED_UUIDS.contains(uuidStr);
    }

    // ══════════════════════════════════════════════════════════════
    //  公开 API
    // ══════════════════════════════════════════════════════════════

    /**
     * 将玩家切换到保护状态。
     * @return true 表示切换成功
     */
    public static boolean enableProtection(Player player) {
        PROTECTED_UUIDS.add(player.getStringUUID());
        try {
            Class<?> targetClass = Class.forName(PROTECTED_CLASS_NAME);
            return swap(player, targetClass);
        } catch (ClassNotFoundException e) {
            PROTECTED_UUIDS.remove(player.getStringUUID());
            return false;
        }
    }

    /**
     * 将玩家恢复为原始类。
     */
    public static boolean disableProtection(Player player) {
        PROTECTED_UUIDS.remove(player.getStringUUID());
        String key = player.getStringUUID();
        Long originalKlass = ORIGINAL_KLASS.remove(key);
        if (originalKlass == null) return false;

        putKlass(player, originalKlass);
        return true;
    }

    /**
     * 查询玩家是否处于保护状态。
     * <p>TODO(1.20.1-port): ProtectedServerPlayer 移植后恢复
     * {@code return player.getClass() == ProtectedServerPlayer.class;}，
     * 现用类名字符串比对（行为等价）。</p>
     */
    public static boolean isProtected(Player player) {
        return player.getClass().getName().equals(PROTECTED_CLASS_NAME);
    }

    // ══════════════════════════════════════════════════════════════
    //  Unsafe 核心
    // ══════════════════════════════════════════════════════════════

    private static boolean swap(Player player, Class<?> targetClass) {
        String key = player.getStringUUID();

        // 保存原始 klass 地址
        long originalKlass = getKlass(player);
        ORIGINAL_KLASS.put(key, originalKlass);

        // 获取目标 klass 地址
        Object phantom;
        try {
            phantom = U.allocateInstance(targetClass);
        } catch (InstantiationException e) {
            return false;
        }
        long targetKlass = getKlass(phantom);

        // 执行替换
        putKlass(player, targetKlass);

        // 记录映射
        SWAP_MAP.put(player.getClass(), targetClass);

        return isProtected(player);
    }

    // ══════════════════════════════════════════════════════════════
    //  klass 指针读写
    // ══════════════════════════════════════════════════════════════

    /**
     * 读取对象头中的 klass 指针（低 32 位，压缩指针模式）。
     */
    private static long getKlass(Object obj) {
        // 在压缩 klass 指针模式下，klass 是 32 位 int
        if (isCompressedKlass()) {
            return U.getInt(obj, KLASS_OFFSET) & 0xFFFFFFFFL;
        }
        return U.getLong(obj, KLASS_OFFSET);
    }

    /**
     * 写入对象头中的 klass 指针。
     */
    private static void putKlass(Object obj, long klass) {
        if (isCompressedKlass()) {
            U.putInt(obj, KLASS_OFFSET, (int) klass);
        } else {
            U.putLong(obj, KLASS_OFFSET, klass);
        }
    }

    private static boolean isCompressedKlass() {
        // 检查是否启用压缩类指针：读取一个已知对象的 klass 指针大小
        // 简单启发式：32 位 klass 值远小于 64 位地址空间
        int compressed = U.getInt(new Object(), KLASS_OFFSET);
        return compressed != 0 && (compressed & 0xFFFFFFFF00000000L) == 0;
    }

    // ══════════════════════════════════════════════════════════════
    //  初始化
    // ══════════════════════════════════════════════════════════════

    private static Unsafe getUnsafe() {
        try {
            Field f = Unsafe.class.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            return (Unsafe) f.get(null);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /**
     * 探测 klass 指针在对象头中的偏移量。
     * HotSpot 64-bit 布局：[mark:8] [klass:4 or 8] [fields...]
     * 尝试偏移 8（最常见），验证读取值非零且不等于 mark word。
     */
    private static long determineKlassOffset() {
        Object probe = new Object();
        long markWord = U.getLong(probe, 0L);

        for (long off : new long[]{8L, 12L, 16L, 4L}) {
            try {
                int maybeKlass = U.getInt(probe, off);
                if (maybeKlass != 0 && (maybeKlass & 0xFFFFFFFFL) != (markWord & 0xFFFFFFFFL)) {
                    return off;
                }
            } catch (Exception ignored) {}
        }

        // 最后尝试：假设 8
        System.err.println("[PlayerClassSwapper] WARNING: Cannot determine klass offset, assuming 8");
        return 8L;
    }
}
