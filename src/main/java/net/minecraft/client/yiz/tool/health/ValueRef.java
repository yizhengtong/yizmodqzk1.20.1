package net.minecraft.client.yiz.tool.health;

import java.lang.reflect.Field;

/**
 * 内存数值引用抽象（P0.5 全量直改用）：对「实体内存中任意可读写的数值」的统一访问。
 * 实现包括：实例字段、DataItem 通道、静态藏血 Map、NBT 数值键等。
 */
public interface ValueRef {

    /** 读当前值；失败返回 NaN。 */
    double read();

    /** 写值；返回是否成功。 */
    boolean write(double v);

    /** 人类可读描述（诊断用）。 */
    String describe();

    /** 实例字段引用。 */
    final class FieldValueRef implements ValueRef {
        private final Object owner;
        private final Field field;

        public FieldValueRef(Object owner, Field field) {
            this.owner = owner;
            this.field = field;
        }

        @Override
        public double read() {
            try {
                Class<?> t = field.getType();
                if (t == float.class) return field.getFloat(owner);
                if (t == double.class) return field.getDouble(owner);
                if (t == int.class) return field.getInt(owner);
                if (t == long.class) return field.getLong(owner);
                return Double.NaN;
            } catch (Throwable t) {
                return Double.NaN;
            }
        }

        @Override
        public boolean write(double v) {
            try {
                Class<?> t = field.getType();
                if (t == float.class) field.setFloat(owner, (float) v);
                else if (t == double.class) field.setDouble(owner, v);
                else if (t == int.class) field.setInt(owner, (int) v);
                else if (t == long.class) field.setLong(owner, (long) v);
                else return false;
                return true;
            } catch (Throwable t) {
                return false;
            }
        }

        @Override
        public String describe() {
            return field.getDeclaringClass().getSimpleName() + "#" + field.getName();
        }
    }
}
