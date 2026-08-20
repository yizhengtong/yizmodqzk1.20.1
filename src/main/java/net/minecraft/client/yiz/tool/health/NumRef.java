package net.minecraft.client.yiz.tool.health;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.LivingEntity;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * 数值门引用抽象（门控击穿/猎杀的数值版）：对「实体内存中任意可读写的数值」的统一访问。
 * 实现包括：NBT 数值键（double/float/int）、实例数值字段。
 *
 * <p>部分模组用「数值 == 0」或「数值阈值」作受击/扣血门控（例如受击无敌计时为 0 时才限伤）。
 * 击穿方式：把该数值<b>钉到极大值</b>，使「==0 / <阈值」判定失效，从而跳过限伤分支。
 * 与 {@link BoolRef} 互补，全程行为验证、不依赖字段名。</p>
 */
public interface NumRef {

    double read();

    boolean write(double v);

    String describe();

    /** NBT 数值键（TAG_DOUBLE / TAG_FLOAT / TAG_INT）。 */
    final class NbtNum implements NumRef {
        private final CompoundTag tag;
        private final String key;
        private final int type;

        public NbtNum(CompoundTag tag, String key, int type) {
            this.tag = tag;
            this.key = key;
            this.type = type;
        }

        @Override
        public double read() {
            switch (type) {
                case Tag.TAG_DOUBLE: return tag.getDouble(key);
                case Tag.TAG_FLOAT: return tag.getFloat(key);
                default: return tag.getInt(key);
            }
        }

        @Override
        public boolean write(double v) {
            try {
                switch (type) {
                    case Tag.TAG_DOUBLE: tag.putDouble(key, v); break;
                    case Tag.TAG_FLOAT: tag.putFloat(key, (float) v); break;
                    default: tag.putInt(key, (int) Math.round(v)); break;
                }
                return true;
            } catch (Throwable t) {
                return false;
            }
        }

        @Override
        public String describe() {
            return "nbtnum:" + key;
        }
    }

    /** 实例数值字段（float/double/int，非 static/final）。 */
    final class FieldNum implements NumRef {
        private final Object owner;
        private final Field field;

        public FieldNum(Object owner, Field field) {
            this.owner = owner;
            this.field = field;
        }

        @Override
        public double read() {
            try {
                Class<?> t = field.getType();
                if (t == float.class) return field.getFloat(owner);
                if (t == double.class) return field.getDouble(owner);
                return field.getInt(owner);
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
                else field.setInt(owner, (int) Math.round(v));
                return true;
            } catch (Throwable t) {
                return false;
            }
        }

        @Override
        public String describe() {
            return "fieldnum:" + field.getDeclaringClass().getSimpleName() + "#" + field.getName();
        }
    }

    /** 收集实体的全部数值门候选：NBT 数值键（double/float/int）→ 实体层级数值字段。 */
    static List<NumRef> candidates(LivingEntity entity) {
        List<NumRef> out = new ArrayList<>();
        if (entity == null) return out;
        try {
            CompoundTag tag = entity.getPersistentData();
            for (String key : tag.getAllKeys()) {
                byte type = tag.getTagType(key);
                if (type == Tag.TAG_DOUBLE || type == Tag.TAG_FLOAT || type == Tag.TAG_INT) {
                    out.add(new NbtNum(tag, key, type));
                }
            }
        } catch (Throwable ignored) {}
        try {
            for (Class<?> c = entity.getClass(); c != null && c != LivingEntity.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    int m = f.getModifiers();
                    if (Modifier.isStatic(m) || Modifier.isFinal(m) || f.isSynthetic()) continue;
                    Class<?> t = f.getType();
                    if (t != float.class && t != double.class && t != int.class) continue;
                    try {
                        f.setAccessible(true);
                        out.add(new FieldNum(entity, f));
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
        return out;
    }
}
