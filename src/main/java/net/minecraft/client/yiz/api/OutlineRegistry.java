package net.minecraft.client.yiz.api;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 通用描边注册表：查询实体是否需要描边及描边色。
 * 优先级：实体自身实现 {@link OutlineEntity} 接口 → 按实体类型默认色 → 动态 Provider（锁定系统等）。
 * {@code LockOutlineBufferSource} 在实体渲染时用 {@link #getOutlineColor} 决定是否双写描边 FBO。
 */
public final class OutlineRegistry {

    /** 动态描边提供者：按实体返回描边色 [r,g,b,a]，null = 不描边。 */
    @FunctionalInterface
    public interface Provider {
        float[] getOutlineColor(Entity entity);
    }

    private static final List<Provider> PROVIDERS = new CopyOnWriteArrayList<>();
    private static final Map<EntityType<?>, float[]> DEFAULT_COLORS = new ConcurrentHashMap<>();

    private OutlineRegistry() {}

    /** 注册动态描边提供者。 */
    public static void register(Provider provider) {
        PROVIDERS.add(provider);
    }

    public static void unregister(Provider provider) {
        PROVIDERS.remove(provider);
    }

    /** 按实体类型注册默认描边色 [r,g,b,a]（该类型所有实例生效）。 */
    public static void registerDefault(EntityType<?> type, float[] color) {
        DEFAULT_COLORS.put(type, color);
    }

    /** 查询实体的描边色；不描边返回 null。 */
    public static float[] getOutlineColor(Entity entity) {
        if (entity instanceof OutlineEntity oe) {
            float[] c = oe.getOutlineColor();
            if (c != null) return c;
        }
        float[] d = DEFAULT_COLORS.get(entity.getType());
        if (d != null) return d;
        for (Provider p : PROVIDERS) {
            float[] c = p.getOutlineColor(entity);
            if (c != null) return c;
        }
        return null;
    }
}
