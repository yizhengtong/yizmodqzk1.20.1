package net.minecraft.client.yiz.tool.health;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.world.entity.LivingEntity;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 实体健康通道扫描器（1.20.1 移植版）。
 *
 * <p>扫描实体类层级上的所有静态 {@link EntityDataAccessor}&lt;Float&gt; 字段，
 * 用于伤害时覆盖所有可能的血量 DataParameter。</p>
 *
 * <p>⚠️ 1.20.1 差异：{@link EntityDataAccessor} 用 {@code getId()}/{@code getSerializer()}
 * （1.21.1 是 {@code id()}/{@code serializer()}）。</p>
 */
public final class HealthChannelScanner {

    private static final Map<Class<?>, List<EntityDataAccessor<Float>>> CHANNEL_CACHE = new ConcurrentHashMap<>();

    private static final EntityDataAccessor<Float> VANILLA_HEALTH_ACCESSOR = initVanillaHealthAccessor();

    @SuppressWarnings("unchecked")
    private static EntityDataAccessor<Float> initVanillaHealthAccessor() {
        try {
            Field f = LivingEntity.class.getDeclaredField("DATA_HEALTH_ID");
            f.setAccessible(true);
            return (EntityDataAccessor<Float>) f.get(null);
        } catch (Exception ignored) {
            return null;
        }
    }

    private HealthChannelScanner() {}

    /** 获取原版血量通道 DATA_HEALTH_ID（可能为 null，若反射失败）。 */
    public static EntityDataAccessor<Float> getVanillaHealthAccessor() {
        return VANILLA_HEALTH_ACCESSOR;
    }

    /** 获取实体所有 Float 类型 DataParameter（排除 vanilla DATA_HEALTH_ID），按类缓存。 */
    public static List<EntityDataAccessor<Float>> getFloatChannels(LivingEntity entity) {
        return CHANNEL_CACHE.computeIfAbsent(entity.getClass(), HealthChannelScanner::scanClassHierarchy);
    }

    /** 获取实体所有 Float 类型 DataParameter（含 vanilla DATA_HEALTH_ID）。 */
    public static List<EntityDataAccessor<Float>> getAllFloatChannels(LivingEntity entity) {
        List<EntityDataAccessor<Float>> all = new ArrayList<>();
        if (VANILLA_HEALTH_ACCESSOR != null) {
            all.add(VANILLA_HEALTH_ACCESSOR);
        }
        all.addAll(getFloatChannels(entity));
        return all;
    }

    /** 清除缓存（类重载时调用）。 */
    public static void clearCache() {
        CHANNEL_CACHE.clear();
    }

    private static List<EntityDataAccessor<Float>> scanClassHierarchy(Class<?> clazz) {
        List<EntityDataAccessor<Float>> result = new ArrayList<>();
        scanUpToLivingEntity(clazz, result);
        return List.copyOf(result);
    }

    private static void scanUpToLivingEntity(Class<?> clazz, List<EntityDataAccessor<Float>> result) {
        if (clazz == null || clazz == Object.class || clazz == LivingEntity.class) return;
        scanUpToLivingEntity(clazz.getSuperclass(), result);
        scanInterfaces(clazz, result);
        scanDeclaredFloatAccessors(clazz, result);
    }

    private static void scanInterfaces(Class<?> clazz, List<EntityDataAccessor<Float>> result) {
        for (Class<?> iface : clazz.getInterfaces()) {
            if (iface == LivingEntity.class || iface == Object.class) continue;
            scanDeclaredFloatAccessors(iface, result);
            scanInterfaces(iface, result);
        }
    }

    private static void scanDeclaredFloatAccessors(Class<?> clazz, List<EntityDataAccessor<Float>> result) {
        for (Field field : clazz.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) continue;
            if (!EntityDataAccessor.class.isAssignableFrom(field.getType())) continue;
            try {
                field.setAccessible(true);
                EntityDataAccessor<?> accessor = (EntityDataAccessor<?>) field.get(null);
                if (accessor == null) continue;
                if (accessor.getSerializer() != EntityDataSerializers.FLOAT) continue;
                @SuppressWarnings("unchecked")
                EntityDataAccessor<Float> floatAccessor = (EntityDataAccessor<Float>) accessor;
                if (VANILLA_HEALTH_ACCESSOR != null && accessor.getId() == VANILLA_HEALTH_ACCESSOR.getId()) continue;
                result.add(floatAccessor);
            } catch (Exception ignored) {
            }
        }
    }
}
