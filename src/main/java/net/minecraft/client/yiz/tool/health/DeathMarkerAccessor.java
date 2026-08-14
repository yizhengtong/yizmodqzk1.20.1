package net.minecraft.client.yiz.tool.health;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.world.entity.LivingEntity;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 通用「强制判死标记」检测 + 篡改（涨跌多空的 coremod 型实体兜底分支）。
 *
 * <p>部分模组（coremod 软 getHealth 型）用一个 <b>Boolean DataParameter 作强制判死标记</b>，
 * 由 coremod 注入的拦截器在 {@code getHealth/isAlive/isDeadOrDying} <b>方法开头</b>读取：
 * 标记为 true 时直接返回 0 / false / true（优先级高于软压、护甲无敌、混淆串藏血）。</p>
 *
 * <p>本类用<b>纯行为验证</b>识别（不依赖字段名/类名）：枚举实体所有 Boolean DataParameter，
 * 逐个「设 true → 看 isAlive 是否变 false → 还原」，命中即该 Boolean 是强制判死标记。</p>
 * <p>篡改就是直接设 true——绕过目标一切保护，强制判死。</p>
 */
public final class DeathMarkerAccessor {

    private static final Map<String, EntityDataAccessor<Boolean>> CACHE = new ConcurrentHashMap<>();
    private static final Set<String> NEGATIVE = ConcurrentHashMap.newKeySet();

    private DeathMarkerAccessor() {}

    // ==================== 检测 ====================

    /** 检测目标实体的「强制判死标记」Boolean DataParameter；无则返回 null（缓存）。 */
    public static EntityDataAccessor<Boolean> detect(LivingEntity entity) {
        if (entity == null || entity.level().isClientSide()) return null;
        String key = entity.getClass().getName();
        EntityDataAccessor<Boolean> cached = CACHE.get(key);
        if (cached != null) return cached;
        if (NEGATIVE.contains(key)) return null;

        EntityDataAccessor<Boolean> marker = doDetect(entity);
        if (marker != null) {
            CACHE.put(key, marker);
        } else {
            NEGATIVE.add(key);
        }
        return marker;
    }

    private static EntityDataAccessor<Boolean> doDetect(LivingEntity entity) {
        if (entity.isDeadOrDying()) return null;
        try {
            List<Field> boolFields = new ArrayList<>();
            collectBoolAccessors(entity, boolFields);
            boolean wasAlive = entity.isAlive();
            if (!wasAlive) return null;

            for (Field f : boolFields) {
                EntityDataAccessor<Boolean> acc = acc(f);
                if (acc == null) continue;
                boolean original;
                try {
                    original = entity.getEntityData().get(acc);
                } catch (Throwable ignored) {
                    continue;
                }
                if (original) continue;  // 已是 true，非初始 false 的死亡标记
                boolean wrote = setBool(entity, acc, true);   // 设 true 测试
                boolean aliveAfter = entity.isAlive();
                setBool(entity, acc, original);                // 还原
                if (wrote && !aliveAfter) return acc;          // 设 true → isAlive 变 false = 强制判死标记
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** 枚举实体类层级所有静态 Boolean DataAccessor 字段。 */
    private static void collectBoolAccessors(LivingEntity entity, List<Field> boolFields) {
        for (Class<?> c = entity.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.isSynthetic() || !Modifier.isStatic(f.getModifiers())) continue;
                if (!EntityDataAccessor.class.isAssignableFrom(f.getType())) continue;
                try {
                    f.setAccessible(true);
                    EntityDataAccessor<?> acc = (EntityDataAccessor<?>) f.get(null);
                    if (acc != null && acc.getSerializer() == EntityDataSerializers.BOOLEAN) {
                        boolFields.add(f);
                    }
                } catch (Throwable ignored) {}
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> EntityDataAccessor<T> acc(Field f) {
        try {
            f.setAccessible(true);
            return (EntityDataAccessor<T>) f.get(null);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean setBool(LivingEntity entity, EntityDataAccessor<Boolean> acc, boolean value) {
        try {
            entity.getEntityData().set(acc, value);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ==================== 篡改 ====================

    /** 设强制判死标记为 true，绕过软压/护甲/混淆串强制判死。非 coremod 判死标记实体返回 false。 */
    public static boolean tamperToDead(LivingEntity entity) {
        EntityDataAccessor<Boolean> marker = detect(entity);
        if (marker == null) return false;
        return setBool(entity, marker, true);
    }
}
