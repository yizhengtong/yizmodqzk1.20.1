package net.minecraft.client.yiz.tool.health;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.LivingEntity;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 直接数据访问保底方案（1.20.1 移植版）。
 *
 * <p>通过反射直接访问 {@link SynchedEntityData} 内部的 DataItem 集合，
 * 绕过所有实体方法覆盖、事件系统、以及 {@code getHealth()} 重写。</p>
 *
 * <p>⚠️ 1.20.1 差异：
 * <ul>
 *   <li>{@link EntityDataAccessor} 用 {@code getSerializer()}/{@code getId()}（1.21.1 是 record）。</li>
 *   <li>{@code SynchedEntityData.itemsById} 在 <b>1.21.1 是 {@code DataItem[]} 数组</b>，
 *       <b>1.20.1 是 {@code Int2ObjectMap<DataItem<?>>}（fastutil Map）</b>——1.21.1 移植的数组强转
 *       在 1.20.1 抛 ClassCastException 被静默吞掉，导致保底层完全失效。本版按 Map 遍历（兼容数组）。</li>
 *   <li>delta 通道字段（yizmodqzk$FE_GET_HEALTH_DATA）在 1.20.1 未由 mixin 注入 → DELTA_ACCESSOR_ID=-1，
 *       仅跳过逻辑不触发（不影响常规伤害）。</li>
 * </ul></p>
 */
public final class DirectHealthFallback {

    private static final Field ITEMS_BY_ID;
    private static final Field IS_DIRTY;
    private static final Method ON_SYNCED_DATA_UPDATED;
    private static final boolean AVAILABLE;

    /** delta 通道 accessor id（1.20.1 未注入 → -1）。 */
    public static final int DELTA_ACCESSOR_ID = initDeltaAccessorId();

    /** 原版血量通道 DATA_HEALTH_ID（反射获取，失败为 null）。 */
    public static final EntityDataAccessor<Float> VANILLA_HEALTH_ACCESSOR = initVanillaHealthAccessor();

    private static EntityDataAccessor<Float> initVanillaHealthAccessor() {
        try {
            Field f = LivingEntity.class.getDeclaredField("DATA_HEALTH_ID");
            f.setAccessible(true);
            return (EntityDataAccessor<Float>) f.get(null);
        } catch (Exception e) {
            return null;
        }
    }

    private static int initDeltaAccessorId() {
        try {
            Field f = LivingEntity.class.getDeclaredField("yizmodqzk$FE_GET_HEALTH_DATA");
            f.setAccessible(true);
            EntityDataAccessor<?> accessor = (EntityDataAccessor<?>) f.get(null);
            return accessor != null ? accessor.getId() : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    static {
        Field itemsField = null;
        Field dirtyField = null;
        Method syncMethod = null;

        try {
            try {
                itemsField = SynchedEntityData.class.getDeclaredField("itemsById");
            } catch (NoSuchFieldException e) {
                for (Field f : SynchedEntityData.class.getDeclaredFields()) {
                    if (f.getType().isArray()) {
                        itemsField = f;
                        break;
                    }
                }
            }
            if (itemsField != null) {
                itemsField.setAccessible(true);
                try {
                    dirtyField = SynchedEntityData.class.getDeclaredField("isDirty");
                } catch (NoSuchFieldException e) {
                    for (Field f : SynchedEntityData.class.getDeclaredFields()) {
                        if (f.getType() == boolean.class) {
                            dirtyField = f;
                            break;
                        }
                    }
                }
                if (dirtyField != null) dirtyField.setAccessible(true);
                try {
                    syncMethod = LivingEntity.class.getMethod("onSyncedDataUpdated", EntityDataAccessor.class);
                } catch (NoSuchMethodException e) {
                    for (Method m : LivingEntity.class.getMethods()) {
                        if (m.getParameterCount() == 1
                            && EntityDataAccessor.class.isAssignableFrom(m.getParameterTypes()[0])
                            && m.getReturnType() == void.class) {
                            syncMethod = m;
                            break;
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[yizmodqzk] DirectHealthFallback reflection init failed: " + e.getMessage());
        }

        ITEMS_BY_ID = itemsField;
        IS_DIRTY = dirtyField;
        ON_SYNCED_DATA_UPDATED = syncMethod;
        AVAILABLE = itemsField != null;
    }

    private DirectHealthFallback() {}

    /** 对所有 Float DataItem 直接施加伤害（amount<0）。绕过所有方法覆盖，最终保底。 */
    public static void damageAll(LivingEntity entity, float amount) {
        if (!AVAILABLE) return;
        if (amount >= 0) return;
        applyToAllFloatItems(entity, amount, true);
    }

    /** 对所有 Float DataItem 直接施加治疗（amount>0）。 */
    public static void healAll(LivingEntity entity, float amount) {
        if (!AVAILABLE) return;
        if (amount <= 0) return;
        applyToAllFloatItems(entity, amount, false);
    }

    /**
     * 对指定 Float 通道直接施加伤害（amount<0 或 任意负值）。
     *
     * <p>绕过 {@code SynchedEntityData.set()}（自定义实体可能覆写 set() 限伤，
     * 如按 DataParameter 存血量的实体），直接改 DataItem 内部值。</p>
     *
     * @return 是否找到并修改了该通道
     */
    public static boolean damageFloatChannel(LivingEntity entity, EntityDataAccessor<Float> accessor, float amount) {
        if (!AVAILABLE || accessor == null || amount >= 0) return false;
        boolean[] found = {false};
        forEachFloatItem(entity, (acc, value, item) -> {
            if (acc.getId() == accessor.getId()) {
                float newValue = Math.max(0, value + amount);
                item.setValue(newValue);
                item.setDirty(true);
                found[0] = true;
            }
        });
        if (found[0] && IS_DIRTY != null) {
            try {
                IS_DIRTY.set(entity.getEntityData(), true);
            } catch (Exception ignored) {}
        }
        return found[0];
    }

    /**
     * 直接修改原版血量通道 DATA_HEALTH_ID（绕过 set() 限伤）。
     * 对「血量存 DataParameter」的自研实体，这是直改主血量通道的最终手段。
     *
     * @return true 已找到并修改 vanilla 血量 DataItem
     */
    public static boolean damageVanillaHealth(LivingEntity entity, float amount) {
        if (amount >= 0) return false;
        return damageFloatChannel(entity, VANILLA_HEALTH_ACCESSOR, amount);
    }

    /** 遍历实体的所有 Float DataItem。 */
    public static void forEachFloatItem(LivingEntity entity, FloatItemCallback callback) {
        if (!AVAILABLE) return;
        try {
            SynchedEntityData data = entity.getEntityData();
            List<SynchedEntityData.DataItem<?>> items = allDataItems(data);
            if (items == null) return;
            for (SynchedEntityData.DataItem<?> item : items) {
                if (item == null) continue;
                EntityDataAccessor<?> accessor = item.getAccessor();
                if (accessor == null || accessor.getSerializer() != EntityDataSerializers.FLOAT) continue;
                @SuppressWarnings("unchecked")
                SynchedEntityData.DataItem<Float> floatItem = (SynchedEntityData.DataItem<Float>) item;
                float value = (Float) item.getValue();
                @SuppressWarnings("unchecked")
                EntityDataAccessor<Float> floatAccessor = (EntityDataAccessor<Float>) accessor;
                callback.accept(floatAccessor, value, floatItem);
            }
        } catch (Exception ignored) {}
    }

    @FunctionalInterface
    public interface FloatItemCallback {
        void accept(EntityDataAccessor<Float> accessor, float value, SynchedEntityData.DataItem<Float> item);
    }

    /**
     * 读取实体 SynchedEntityData 内部所有 DataItem。
     *
     * <p>兼容两种版本结构：1.21.1 的 {@code DataItem[]} 数组，与 1.20.1 的
     * {@code Int2ObjectMap<DataItem<?>>}（fastutil Map）。数组强转在 1.20.1 会
     * ClassCastException，故统一按集合提取。</p>
     */
    @SuppressWarnings("unchecked")
    private static List<SynchedEntityData.DataItem<?>> allDataItems(SynchedEntityData data) {
        List<SynchedEntityData.DataItem<?>> result = new ArrayList<>();
        if (data == null || ITEMS_BY_ID == null) return result;
        Object raw = null;
        try {
            raw = ITEMS_BY_ID.get(data);
        } catch (Exception ignored) {
            return result;
        }
        if (raw instanceof SynchedEntityData.DataItem<?>[] array) {
            for (SynchedEntityData.DataItem<?> item : array) {
                if (item != null) result.add(item);
            }
        } else if (raw instanceof java.util.Map<?, ?> map) {
            for (Object value : map.values()) {
                if (value instanceof SynchedEntityData.DataItem<?> item) {
                    result.add(item);
                }
            }
        }
        return result;
    }

    private static void applyToAllFloatItems(LivingEntity entity, float amount, boolean clamp) {
        try {
            SynchedEntityData data = entity.getEntityData();
            List<SynchedEntityData.DataItem<?>> items = allDataItems(data);
            boolean changed = false;

            for (SynchedEntityData.DataItem<?> item : items) {
                EntityDataAccessor<?> accessor = item.getAccessor();
                if (accessor == null) continue;
                if (accessor.getSerializer() != EntityDataSerializers.FLOAT) continue;
                if (accessor.getId() == DELTA_ACCESSOR_ID) continue;

                float current = (Float) item.getValue();
                float newValue = clamp ? Math.max(0, current + amount) : current + amount;

                @SuppressWarnings("unchecked")
                SynchedEntityData.DataItem<Float> floatItem = (SynchedEntityData.DataItem<Float>) item;
                floatItem.setValue(newValue);
                item.setDirty(true);
                changed = true;
            }

            if (changed) {
                if (ON_SYNCED_DATA_UPDATED != null) {
                    for (SynchedEntityData.DataItem<?> item : items) {
                        EntityDataAccessor<?> accessor = item.getAccessor();
                        if (accessor.getSerializer() == EntityDataSerializers.FLOAT) {
                            try {
                                ON_SYNCED_DATA_UPDATED.invoke(entity, accessor);
                            } catch (Exception ignored) {}
                        }
                    }
                }
                if (IS_DIRTY != null) {
                    IS_DIRTY.set(data, true);
                }
            }
        } catch (Exception ignored) {
        }
    }
}
