package net.minecraft.client.yiz.tool.key;

import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * 字段内存句柄：对任意静态/实例字段做 Unsafe 直读直写。
 *
 * <p><b>全流程不需要 {@code setAccessible}</b>：{@code staticFieldOffset / objectFieldOffset}
 * 只要求拿到 {@link Field} 对象（{@code getDeclaredFields()} 本身就是合法 API），
 * 读写走 Unsafe —— private/final 一概无视，且栈上不出现反射帧，
 * 目标模组的「调用栈鉴权」无法观察到本次读取。</p>
 */
@SuppressWarnings("removal")
public final class FieldHandle {

    private static final Unsafe U = UnsafeAccess.get();

    private final Class<?> owner;
    private final String name;
    private final boolean isStatic;
    private final Object base;      // 静态字段 → staticFieldBase；实例字段 → null（读时传实例）
    private final long offset;
    private final Class<?> type;

    private FieldHandle(Class<?> owner, String name, boolean isStatic, Object base, long offset, Class<?> type) {
        this.owner = owner;
        this.name = name;
        this.isStatic = isStatic;
        this.base = base;
        this.offset = offset;
        this.type = type;
    }

    /** 由任意 Field 构造句柄；不可用/失败返回 null（不抛，便于批量普查）。 */
    public static FieldHandle of(Field f) {
        if (f == null || U == null) return null;
        try {
            boolean isStatic = Modifier.isStatic(f.getModifiers());
            Object base = null;
            long offset;
            if (isStatic) {
                base = U.staticFieldBase(f);
                offset = U.staticFieldOffset(f);
            } else {
                offset = U.objectFieldOffset(f);
            }
            return new FieldHandle(f.getDeclaringClass(), f.getName(), isStatic, base, offset, f.getType());
        } catch (Throwable t) {
            return null;
        }
    }

    public Class<?> owner() { return owner; }
    public String name() { return name; }
    public boolean isStatic() { return isStatic; }
    public long offset() { return offset; }
    public Class<?> type() { return type; }

    // ==================== 读取 ====================

    public Object getObject() {
        if (U == null) throw UnsafeAccess.unavailable();
        return U.getObject(base, offset);
    }

    public Object getObject(Object instance) {
        if (U == null) throw UnsafeAccess.unavailable();
        return U.getObject(instance, offset);
    }

    public long getLong(Object instance) {
        if (U == null) throw UnsafeAccess.unavailable();
        return U.getLong(instance, offset);
    }

    public int getInt(Object instance) {
        if (U == null) throw UnsafeAccess.unavailable();
        return U.getInt(instance, offset);
    }

    /** 安全读取（任意失败返回 null，普查用）。 */
    public Object tryGetObject() {
        try {
            return getObject();
        } catch (Throwable t) {
            return null;
        }
    }

    // ==================== 写入 ====================

    public void putObject(Object value) {
        if (U == null) throw UnsafeAccess.unavailable();
        U.putObject(base, offset, value);
    }

    public void putObject(Object instance, Object value) {
        if (U == null) throw UnsafeAccess.unavailable();
        U.putObject(instance, offset, value);
    }

    /** 安全写入（任意失败返回 false，批量中和用）。 */
    public boolean tryPutObject(Object value) {
        try {
            putObject(value);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public String describe() {
        return owner.getName() + "." + name + (isStatic ? " (static)" : " (instance)")
                + " : " + type.getSimpleName() + " @0x" + Long.toHexString(offset);
    }
}
