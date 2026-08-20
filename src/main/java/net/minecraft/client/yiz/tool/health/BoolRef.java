package net.minecraft.client.yiz.tool.health;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.LivingEntity;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * 布尔门控引用抽象（P0.5 门控击穿/猎杀用）：对「实体内存中任意可读写的布尔」的统一访问。
 * 实现包括：NBT 布尔键、Boolean DataParameter、实例 boolean 字段。
 */
public interface BoolRef {

    boolean read();

    boolean write(boolean v);

    String describe();

    /** NBT 布尔键（PersistentData TAG_BYTE 0/1）。 */
    final class NbtBool implements BoolRef {
        private final CompoundTag tag;
        private final String key;

        public NbtBool(CompoundTag tag, String key) {
            this.tag = tag;
            this.key = key;
        }

        @Override
        public boolean read() {
            return tag.getBoolean(key);
        }

        @Override
        public boolean write(boolean v) {
            tag.putBoolean(key, v);
            return true;
        }

        @Override
        public String describe() {
            return "nbt:" + key;
        }
    }

    /** Boolean DataParameter 通道。 */
    final class DataBool implements BoolRef {
        private final LivingEntity entity;
        private final EntityDataAccessor<Boolean> accessor;

        public DataBool(LivingEntity entity, EntityDataAccessor<Boolean> accessor) {
            this.entity = entity;
            this.accessor = accessor;
        }

        @Override
        public boolean read() {
            Boolean v = DirectHealthFallback.readBooleanChannel(entity, accessor);
            return v != null && v;
        }

        @Override
        public boolean write(boolean v) {
            return DirectHealthFallback.setBooleanChannelValue(entity, accessor, v, true);
        }

        @Override
        public String describe() {
            return "data:" + accessor;
        }
    }

    /** 实例 boolean 字段。 */
    final class FieldBool implements BoolRef {
        private final Object owner;
        private final Field field;

        public FieldBool(Object owner, Field field) {
            this.owner = owner;
            this.field = field;
        }

        @Override
        public boolean read() {
            try {
                return field.getBoolean(owner);
            } catch (Throwable t) {
                return false;
            }
        }

        @Override
        public boolean write(boolean v) {
            try {
                field.setBoolean(owner, v);
                return true;
            } catch (Throwable t) {
                return false;
            }
        }

        @Override
        public String describe() {
            return "field:" + field.getDeclaringClass().getSimpleName() + "#" + field.getName();
        }
    }

    /** 收集实体的全部布尔门控候选：NBT 布尔 → Boolean DataParameter → 实体层级 boolean 字段。 */
    static List<BoolRef> candidates(LivingEntity entity) {
        List<BoolRef> out = new ArrayList<>();
        if (entity == null) return out;
        // 1. NBT 布尔（PersistentData TAG_BYTE 0/1）
        try {
            CompoundTag tag = entity.getPersistentData();
            for (String key : tag.getAllKeys()) {
                if (tag.getTagType(key) == Tag.TAG_BYTE) {
                    byte b = tag.getByte(key);
                    if (b == 0 || b == 1) out.add(new NbtBool(tag, key));
                }
            }
        } catch (Throwable ignored) {}
        // 2. Boolean DataParameter
        try {
            DirectHealthFallback.forEachBooleanItem(entity, (acc, value, item) -> out.add(new DataBool(entity, acc)));
        } catch (Throwable ignored) {}
        // 3. 实体层级 boolean 字段（不含 LivingEntity 基类）
        try {
            for (Class<?> c = entity.getClass(); c != null && c != LivingEntity.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())
                            || java.lang.reflect.Modifier.isFinal(f.getModifiers())
                            || f.isSynthetic()) continue;
                    if (f.getType() != boolean.class) continue;
                    try {
                        f.setAccessible(true);
                        out.add(new FieldBool(entity, f));
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
        return out;
    }
}
