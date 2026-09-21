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
 * <p> 1.20.1 差异：{@link EntityDataAccessor} 用 {@code getId()}/{@code getSerializer()}
 * （1.21.1 是 {@code id()}/{@code serializer()}）。</p>
 */
public final class HealthChannelScanner {

    private static final Map<Class<?>, List<EntityDataAccessor<Float>>> CHANNEL_CACHE = new ConcurrentHashMap<>();

    private static final EntityDataAccessor<Float> VANILLA_HEALTH_ACCESSOR = initVanillaHealthAccessor();

    /**
     * 原版血量通道解析（<b>必须走映射无关的解析</b>）。
     *
     * <p>⚠️ 反射用的字符串常量不会被 reobf 重映射：开发环境字段叫 {@code DATA_HEALTH_ID}，
     * 生产环境叫 {@code f_20961_}。这里原本只写 official 名 → 生产解析失败返回 {@code null}；
     * 而调用方（{@code YizxianMob.enforceSecureHealthState} 的 Float 通道清零循环）拿到 null 后
     * <b>失去「跳过 vanilla 血量通道」的守卫</b>，于是每 tick 把真实血量写进通道后又被清零 ——
     * 表现正是「生产环境其它模组看不到血量变化、开发环境正常」。</p>
     *
     * <p>统一委托 {@link DirectHealthFallback#VANILLA_HEALTH_ACCESSOR}（official 名 → SRG 名 →
     * 「LivingEntity 里唯一的 static EntityDataAccessor&lt;Float&gt;」三级解析），让全模组只有一处解析口径。</p>
     */
    private static EntityDataAccessor<Float> initVanillaHealthAccessor() {
        try {
            return DirectHealthFallback.VANILLA_HEALTH_ACCESSOR;
        } catch (Throwable t) {
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
