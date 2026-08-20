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
 * <p> 1.20.1 差异：
 * <ul>
 *   <li>{@link EntityDataAccessor} 用 {@code getSerializer()}/{@code getId()}（1.21.1 是 record）。</li>
 *   <li>{@code SynchedEntityData.itemsById} 在 <b>1.21.1 是 {@code DataItem[]} 数组</b>，
 *       <b>1.20.1 是 {@code Int2ObjectMap<DataItem<?>>}（fastutil Map）</b>——1.21.1 移植的数组强转
 *       在 1.20.1 抛 ClassCastException 被静默吞掉，导致保底层完全失效。本版按 Map 遍历（兼容数组）。</li>
 *   <li>delta 通道字段（yizmodqzk$HEALTH_DELTA）由 LivingEntityMixin 在 defineSynchedData TAIL 注入，
 *       DELTA_ACCESSOR_ID 有效；遍历时跳过 delta 通道防误伤。</li>
 * </ul></p>
 */
public final class DirectHealthFallback {

    private static final Field ITEMS_BY_ID;
    private static final Field IS_DIRTY;
    private static final Method ON_SYNCED_DATA_UPDATED;
    private static final boolean AVAILABLE;

    /** DataItem.value 字段（类型擦除后为 Object；按类型找，双名/混淆免疫）。 */
    private static final Field DATA_ITEM_VALUE_FIELD = findDataItemValueField();

    /** delta 通道 accessor id（1.20.1 未注入 → -1）。 */
    public static final int DELTA_ACCESSOR_ID = initDeltaAccessorId();

    /** 原版血量通道 DATA_HEALTH_ID（反射获取，失败为 null）。 */
    public static final EntityDataAccessor<Float> VANILLA_HEALTH_ACCESSOR = initVanillaHealthAccessor();

    private static EntityDataAccessor<Float> initVanillaHealthAccessor() {
        // 双名匹配（official + SRG）：生产环境字段名是 f_20961_（reobf 不改反射字符串）
        for (String name : new String[]{"DATA_HEALTH_ID", "f_20961_"}) {
            try {
                Field f = LivingEntity.class.getDeclaredField(name);
                f.setAccessible(true);
                return (EntityDataAccessor<Float>) f.get(null);
            } catch (Exception ignored) {}
        }
        // 兜底：按类型找 static EntityDataAccessor<Float> 字段
        try {
            for (Field f : LivingEntity.class.getDeclaredFields()) {
                if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                if (EntityDataAccessor.class.isAssignableFrom(f.getType())
                        && f.getGenericType().getTypeName().contains("Float")) {
                    f.setAccessible(true);
                    return (EntityDataAccessor<Float>) f.get(null);
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static int initDeltaAccessorId() {
        try {
            // delta 通道改从独立 holder 读取（不再反射 LivingEntity 上的 mixin @Unique 字段）
            return HealthChannels.DELTA_HEALTH.getId();
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
                try {
                    itemsField = SynchedEntityData.class.getDeclaredField("f_135345_"); // SRG 名
                } catch (NoSuchFieldException e2) {
                    // 按类型兜底：1.20.1 是 Int2ObjectMap，1.21.1 是 DataItem[] 数组
                    for (Field f : SynchedEntityData.class.getDeclaredFields()) {
                        if (f.getType().isArray() || java.util.Map.class.isAssignableFrom(f.getType())) {
                            itemsField = f;
                            break;
                        }
                    }
                }
            }
            if (itemsField != null) {
                itemsField.setAccessible(true);
                try {
                    dirtyField = SynchedEntityData.class.getDeclaredField("isDirty");
                } catch (NoSuchFieldException e) {
                    try {
                        dirtyField = SynchedEntityData.class.getDeclaredField("f_135348_"); // SRG 名
                    } catch (NoSuchFieldException e2) {
                        for (Field f : SynchedEntityData.class.getDeclaredFields()) {
                            if (f.getType() == boolean.class && !java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                                dirtyField = f;
                                break;
                            }
                        }
                    }
                }
                if (dirtyField != null) dirtyField.setAccessible(true);
                try {
                    syncMethod = LivingEntity.class.getMethod("onSyncedDataUpdated", EntityDataAccessor.class);
                } catch (NoSuchMethodException e) {
                    try {
                        syncMethod = LivingEntity.class.getMethod("m_7350_", EntityDataAccessor.class); // SRG 名
                    } catch (NoSuchMethodException e2) {
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

    /** 按类型定位 {@code DataItem.value} 字段（非静态、类型为 Object —— 泛型 T 擦除）。 */
    private static Field findDataItemValueField() {
        try {
            for (Field f : SynchedEntityData.DataItem.class.getDeclaredFields()) {
                if (!java.lang.reflect.Modifier.isStatic(f.getModifiers()) && f.getType() == Object.class) {
                    return f;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

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
     * 直写指定 Float 通道的值（绕过 {@code set()} 限伤）。
     *
     * @param markDirty 是否标记 dirty；探测/行为验证传 false（不触发原版同步），正式扣血传 true
     * @return 是否找到并修改了该通道
     */
    public static boolean setFloatChannelValue(LivingEntity entity, EntityDataAccessor<Float> accessor, float value, boolean markDirty) {
        if (!AVAILABLE || accessor == null) return false;
        boolean[] found = {false};
        forEachFloatItem(entity, (acc, cur, item) -> {
            if (acc.getId() == accessor.getId()) {
                item.setValue(value);
                if (markDirty) item.setDirty(true);
                found[0] = true;
            }
        });
        return found[0];
    }

    // ==================== 通用数值 / 字符串 DataItem（P0：非 Float 血量通道） ====================

    @FunctionalInterface
    public interface NumericItemCallback {
        void accept(EntityDataAccessor<?> accessor, Number value, SynchedEntityData.DataItem<?> item);
    }

    @FunctionalInterface
    public interface StringItemCallback {
        void accept(EntityDataAccessor<?> accessor, String value, SynchedEntityData.DataItem<?> item);
    }

    /** 遍历实体的 Float/Int/Long DataItem（血量数值通道的通用形态；1.20.1 无 Double 序列化器）。 */
    public static void forEachNumericItem(LivingEntity entity, NumericItemCallback callback) {
        if (!AVAILABLE || callback == null) return;
        try {
            List<SynchedEntityData.DataItem<?>> items = allDataItems(entity.getEntityData());
            if (items == null) return;
            for (SynchedEntityData.DataItem<?> item : items) {
                if (item == null) continue;
                EntityDataAccessor<?> accessor = item.getAccessor();
                if (accessor == null) continue;
                var ser = accessor.getSerializer();
                if (ser != EntityDataSerializers.FLOAT && ser != EntityDataSerializers.INT
                        && ser != EntityDataSerializers.LONG) continue;
                Object value = item.getValue();
                if (!(value instanceof Number n)) continue;
                callback.accept(accessor, n, item);
            }
        } catch (Exception ignored) {}
    }

    /** 遍历实体的 String DataItem（P0：混淆串 / 前缀串血量通道）。 */
    public static void forEachStringItem(LivingEntity entity, StringItemCallback callback) {
        if (!AVAILABLE || callback == null) return;
        try {
            List<SynchedEntityData.DataItem<?>> items = allDataItems(entity.getEntityData());
            if (items == null) return;
            for (SynchedEntityData.DataItem<?> item : items) {
                if (item == null) continue;
                EntityDataAccessor<?> accessor = item.getAccessor();
                if (accessor == null) continue;
                if (accessor.getSerializer() != EntityDataSerializers.STRING) continue;
                Object value = item.getValue();
                if (!(value instanceof String s)) continue;
                callback.accept(accessor, s, item);
            }
        } catch (Exception ignored) {}
    }

    /** 读取任意数值通道的当前值（找不到返回 null）。 */
    public static Number readNumericChannel(LivingEntity entity, EntityDataAccessor<?> accessor) {
        if (!AVAILABLE || accessor == null) return null;
        Number[] out = {null};
        forEachNumericItem(entity, (acc, value, item) -> {
            if (acc.getId() == accessor.getId()) out[0] = value;
        });
        return out[0];
    }

    /** 直写任意数值通道的 DataItem 值（绕过 {@code set()}/{@code setHealth} 门）。
     *  @param markDirty 是否标记 dirty（探测传 false；正式写传 true） */
    public static boolean setNumericChannelValue(LivingEntity entity, EntityDataAccessor<?> accessor,
                                                 Number value, boolean markDirty) {
        if (!AVAILABLE || accessor == null || value == null) return false;
        boolean[] found = {false};
        forEachNumericItem(entity, (acc, cur, item) -> {
            if (acc.getId() == accessor.getId()) {
                Object coerced = coerceNumber(value, cur);
                if (writeItemValue(item, coerced)) {
                    if (markDirty) item.setDirty(true);
                    found[0] = true;
                }
            }
        });
        if (found[0] && markDirty) {
            markEntityDataDirty(entity);
            notifySynced(entity, accessor);
        }
        return found[0];
    }

    /** 对任意数值通道施加伤害（{@code amount < 0}）。返回是否找到并修改。 */
    public static boolean damageNumericChannel(LivingEntity entity, EntityDataAccessor<?> accessor, double amount) {
        if (amount >= 0) return false;
        Number cur = readNumericChannel(entity, accessor);
        if (cur == null) return false;
        double next = Math.max(0, cur.doubleValue() + amount);
        return setNumericChannelValue(entity, accessor, coerceNumber(next, cur), true);
    }

    /**
     * 对实体的所有 Int/Long DataItem 直接施加伤害（{@code amount < 0}；Float 走
     * {@link #damageAll} / {@link #forEachFloatItem}，避免双重扣血）。
     * 覆盖「血量存 INT/LONG 通道且未被定位器命中」的实体（1b 兜底）。
     */
    public static void damageAllNumericItems(LivingEntity entity, double amount) {
        if (!AVAILABLE || amount >= 0) return;
        try {
            List<SynchedEntityData.DataItem<?>> items = allDataItems(entity.getEntityData());
            boolean changed = false;
            for (SynchedEntityData.DataItem<?> item : items) {
                if (item == null) continue;
                EntityDataAccessor<?> accessor = item.getAccessor();
                if (accessor == null) continue;
                var ser = accessor.getSerializer();
                if (ser != EntityDataSerializers.INT && ser != EntityDataSerializers.LONG) continue;
                Object v = item.getValue();
                if (!(v instanceof Number n)) continue;
                double next = Math.max(0, n.doubleValue() + amount);
                if (writeItemValue(item, coerceNumber(next, n))) {
                    item.setDirty(true);
                    notifySynced(entity, accessor);
                    changed = true;
                }
            }
            if (changed) {
                markEntityDataDirty(entity);
            }
        } catch (Exception ignored) {}
    }

    /** 把实体的所有 Int/Long DataItem 清零（死亡链播种用；Float 走 forEachFloatItem）。 */
    public static void zeroAllNumericItems(LivingEntity entity) {
        damageAllNumericItems(entity, -Double.MAX_VALUE);
    }

    /** 直写 String 通道的 DataItem 值（绕过 {@code set()}）。 */
    public static boolean setStringChannelValue(LivingEntity entity, EntityDataAccessor<?> accessor,
                                                String value, boolean markDirty) {
        if (!AVAILABLE || accessor == null || value == null) return false;
        boolean[] found = {false};
        forEachStringItem(entity, (acc, cur, item) -> {
            if (acc.getId() == accessor.getId()) {
                if (writeItemValue(item, value)) {
                    if (markDirty) item.setDirty(true);
                    found[0] = true;
                }
            }
        });
        if (found[0] && markDirty) {
            markEntityDataDirty(entity);
            notifySynced(entity, accessor);
        }
        return found[0];
    }

    // ==================== Boolean DataItem（P0.5：门控探测/击穿用） ====================

    @FunctionalInterface
    public interface BooleanItemCallback {
        void accept(EntityDataAccessor<Boolean> accessor, boolean value, SynchedEntityData.DataItem<?> item);
    }

    /** 遍历实体的所有 Boolean DataItem（门控/判死标记候选）。 */
    public static void forEachBooleanItem(LivingEntity entity, BooleanItemCallback callback) {
        if (!AVAILABLE || callback == null) return;
        try {
            List<SynchedEntityData.DataItem<?>> items = allDataItems(entity.getEntityData());
            if (items == null) return;
            for (SynchedEntityData.DataItem<?> item : items) {
                if (item == null) continue;
                EntityDataAccessor<?> accessor = item.getAccessor();
                if (accessor == null || accessor.getSerializer() != EntityDataSerializers.BOOLEAN) continue;
                Object value = item.getValue();
                if (!(value instanceof Boolean b)) continue;
                @SuppressWarnings("unchecked")
                EntityDataAccessor<Boolean> boolAcc = (EntityDataAccessor<Boolean>) accessor;
                callback.accept(boolAcc, b, item);
            }
        } catch (Exception ignored) {}
    }

    /** 读取 Boolean 通道当前值（找不到返回 null）。 */
    public static Boolean readBooleanChannel(LivingEntity entity, EntityDataAccessor<Boolean> accessor) {
        if (!AVAILABLE || accessor == null) return null;
        Boolean[] out = {null};
        forEachBooleanItem(entity, (acc, value, item) -> {
            if (acc.getId() == accessor.getId()) out[0] = value;
        });
        return out[0];
    }

    /** 直写 Boolean 通道的 DataItem 值（绕过 {@code set()}）。 */
    public static boolean setBooleanChannelValue(LivingEntity entity, EntityDataAccessor<Boolean> accessor,
                                                 boolean value, boolean markDirty) {
        if (!AVAILABLE || accessor == null) return false;
        boolean[] found = {false};
        forEachBooleanItem(entity, (acc, cur, item) -> {
            if (acc.getId() == accessor.getId()) {
                if (writeItemValue(item, value)) {
                    if (markDirty) item.setDirty(true);
                    found[0] = true;
                }
            }
        });
        if (found[0] && markDirty) {
            markEntityDataDirty(entity);
            notifySynced(entity, accessor);
        }
        return found[0];
    }

    /** 按引用值的类型把数值强制转换（Int/Long/Double/Float）。 */
    private static Number coerceNumber(Number value, Object ref) {
        double d = value.doubleValue();
        if (ref instanceof Integer) return (int) Math.round(d);
        if (ref instanceof Long) return (long) Math.round(d);
        if (ref instanceof Double) return d;
        if (ref instanceof Short) return (short) Math.round(d);
        if (ref instanceof Byte) return (byte) Math.round(d);
        return (float) d;
    }

    /** 写 DataItem 值：优先 {@code setValue}（擦除后为 Object），失败降级 Unsafe 直写 value 字段。 */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static boolean writeItemValue(SynchedEntityData.DataItem<?> item, Object value) {
        try {
            SynchedEntityData.DataItem raw = item;   // 通配符捕获：转 raw 调擦除后 setValue(Object)
            raw.setValue(value);
            return true;
        } catch (Throwable t) {
            return writeItemValueUnsafe(item, value);
        }
    }

    /** Unsafe 直写 {@code DataItem.value}（不触发任何 setValue/重写，栈上无反射帧）。 */
    private static boolean writeItemValueUnsafe(SynchedEntityData.DataItem<?> item, Object value) {
        try {
            sun.misc.Unsafe u = net.minecraft.client.yiz.tool.key.UnsafeAccess.get();
            if (u == null || DATA_ITEM_VALUE_FIELD == null) return false;
            DATA_ITEM_VALUE_FIELD.setAccessible(true);
            long off = u.objectFieldOffset(DATA_ITEM_VALUE_FIELD);
            u.putObject(item, off, value);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 标记 SynchedEntityData dirty（内部 isDirty 字段）。 */
    private static void markEntityDataDirty(LivingEntity entity) {
        if (IS_DIRTY == null) return;
        try {
            IS_DIRTY.set(entity.getEntityData(), true);
        } catch (Exception ignored) {}
    }

    /** 通知实体数据更新（onSyncedDataUpdated 反射调用，双名匹配）。 */
    private static void notifySynced(LivingEntity entity, EntityDataAccessor<?> accessor) {
        if (ON_SYNCED_DATA_UPDATED == null) return;
        try {
            ON_SYNCED_DATA_UPDATED.invoke(entity, accessor);
        } catch (Exception ignored) {}
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
