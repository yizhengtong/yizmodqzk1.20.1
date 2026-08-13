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
 * 通用「差值血量 + 独立死亡标记」DataParameter 检测 + 篡改（涨跌多空的动态血防实体分支）。
 *
 * <p>部分实体把血量藏在<b>自定义 DataParameter</b>里，用
 * {@code getHealth() = normal - away} 两个 Float 通道的<b>差值</b>动态计算，且
 * {@code isAlive()/isDeadOrDying()} 读一个<b>独立的 Boolean 死亡标记</b>（不读 getHealth）。
 * 这类实体对「字段级直改 / delta 软压 / 无脑减所有 Float 通道」都免疫。</p>
 *
 * <p>本类用<b>纯行为验证</b>识别（不依赖字段名/类名，可混淆）：</p>
 * <ul>
 *   <li>两个 Float DataAccessor 的差值 {@code |a-b| ≈ getHealth} → 命中「差值血量」；</li>
 *   <li>直写其中一个 {@code +1} 看 getHealth 方向：增 → 被减数（normal），减 → 减数（away）；</li>
 *   <li>一个 Boolean DataAccessor 值 {@code == isDeadOrDying} → 死亡标记。</li>
 * </ul>
 * <p>篡改走 {@link DirectHealthFallback#setFloatChannelValue} 直写 DataItem（绕过目标
 * {@code setExaltedAway} 的 clamp），扣血方向正确：<b>增加 away</b> 而非减少。</p>
 */
public final class DynamicHealthAccessor {

    /** 命中的差值血量通道：away=减数（增加=扣血），normal=被减数（上限），death=死亡标记。 */
    public record Slot(EntityDataAccessor<Float> away, EntityDataAccessor<Float> normal,
                       EntityDataAccessor<Boolean> death) {}

    private static final Map<String, Slot> CACHE = new ConcurrentHashMap<>();
    private static final Set<String> NON_DYNAMIC = ConcurrentHashMap.newKeySet();

    private DynamicHealthAccessor() {}

    // ==================== 检测 ====================

    /** 检测目标是否为「差值血量」实体；是返回 Slot（缓存），否则 null。 */
    public static Slot detect(LivingEntity entity) {
        if (entity == null || entity.level().isClientSide()) return null;
        String key = entity.getClass().getName();
        Slot cached = CACHE.get(key);
        if (cached != null) return cached;
        if (NON_DYNAMIC.contains(key)) return null;

        Slot slot = doDetect(entity);
        if (slot != null) {
            CACHE.put(key, slot);
        } else {
            NON_DYNAMIC.add(key);
        }
        return slot;
    }

    private static Slot doDetect(LivingEntity entity) {
        if (entity.getHealth() <= 0 || entity.isDeadOrDying()) return null;  // 已死无需检测
        try {
            List<Field> floatFields = new ArrayList<>();
            List<Field> boolFields = new ArrayList<>();
            collectAccessorFields(entity, floatFields, boolFields);

            float health = entity.getHealth();
            // 两两组合 Float 通道，找差值 ≈ getHealth
            for (int i = 0; i < floatFields.size(); i++) {
                for (int j = i + 1; j < floatFields.size(); j++) {
                    EntityDataAccessor<Float> a = acc(floatFields.get(i));
                    EntityDataAccessor<Float> b = acc(floatFields.get(j));
                    if (a == null || b == null) continue;
                    float va = entity.getEntityData().get(a);
                    float vb = entity.getEntityData().get(b);
                    float diff = Math.abs(va - vb);
                    if (Math.abs(diff - health) > Math.max(0.5f, health * 0.05f)) continue;
                    // 命中差值 → 区分 normal/away
                    Slot slot = buildSlot(entity, a, b, va, vb, boolFields);
                    if (slot != null) return slot;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** 区分 a/b 谁是 normal（被减数）谁是 away（减数），并找死亡标记。
     *  必须<b>双向</b>验证：normal 直写 +1 → getHealth 增，away 直写 +1 → getHealth 减。
     *  否则「当前血量 + 无关 0 值通道」会被误判成差值血量（如 ImmortalGolem 的 REVIVE_TIMES=0）。 */
    private static Slot buildSlot(LivingEntity entity, EntityDataAccessor<Float> a,
                                  EntityDataAccessor<Float> b, float va, float vb,
                                  List<Field> boolFields) {
        if (isNormal(entity, a, va) && isAway(entity, b, vb)) {
            return new Slot(b, a, findDeathMarker(entity, boolFields));
        }
        if (isAway(entity, a, va) && isNormal(entity, b, vb)) {
            return new Slot(a, b, findDeathMarker(entity, boolFields));
        }
        return null;  // 方向判定失败，或非差值血量
    }

    /** 直写 acc +1 看 getHealth 是否增加：增加 → 被减数（normal）。 */
    private static boolean isNormal(LivingEntity entity, EntityDataAccessor<Float> acc, float cur) {
        try {
            float before = entity.getHealth();
            boolean wrote = DirectHealthFallback.setFloatChannelValue(entity, acc, cur + 1.0f, false);
            float after = entity.getHealth();
            DirectHealthFallback.setFloatChannelValue(entity, acc, cur, false);  // 还原
            return wrote && after > before + 0.5f;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 直写 acc +1 看 getHealth 是否减少：减少 → 减数（away）。区分「真 away」与「无关 0 值通道」。 */
    private static boolean isAway(LivingEntity entity, EntityDataAccessor<Float> acc, float cur) {
        try {
            float before = entity.getHealth();
            boolean wrote = DirectHealthFallback.setFloatChannelValue(entity, acc, cur + 1.0f, false);
            float after = entity.getHealth();
            DirectHealthFallback.setFloatChannelValue(entity, acc, cur, false);  // 还原
            return wrote && after < before - 0.5f;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 找值 == isDeadOrDying 的 Boolean DataAccessor（死亡标记）。 */
    private static EntityDataAccessor<Boolean> findDeathMarker(LivingEntity entity, List<Field> boolFields) {
        boolean dead = entity.isDeadOrDying();
        for (Field f : boolFields) {
            try {
                EntityDataAccessor<Boolean> acc = acc(f);
                if (acc == null) continue;
                Boolean v = entity.getEntityData().get(acc);
                if (v != null && v.booleanValue() == dead) return acc;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    /** 枚举实体类层级所有静态 Float / Boolean DataAccessor 字段。 */
    private static void collectAccessorFields(LivingEntity entity, List<Field> floatFields, List<Field> boolFields) {
        for (Class<?> c = entity.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.isSynthetic() || !Modifier.isStatic(f.getModifiers())) continue;
                if (!EntityDataAccessor.class.isAssignableFrom(f.getType())) continue;
                try {
                    f.setAccessible(true);
                    EntityDataAccessor<?> acc = (EntityDataAccessor<?>) f.get(null);
                    if (acc == null) continue;
                    if (acc.getSerializer() == EntityDataSerializers.FLOAT) floatFields.add(f);
                    else if (acc.getSerializer() == EntityDataSerializers.BOOLEAN) boolFields.add(f);
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

    // ==================== 篡改 ====================

    /**
     * 对「差值血量」实体扣 {@code amount} 血：增加 away（减数），clamp 到 normal；
     * 血量耗尽（away ≥ normal）时同步置死亡标记。非差值血量实体返回 false。
     */
    public static boolean tamper(LivingEntity entity, float amount) {
        if (amount <= 0) return false;
        Slot slot = detect(entity);
        if (slot == null) return false;
        try {
            float normal = entity.getEntityData().get(slot.normal());
            float away = entity.getEntityData().get(slot.away());
            float newAway = Math.min(away + amount, normal);  // 扣血 = 增加 away，clamp 到上限
            DirectHealthFallback.setFloatChannelValue(entity, slot.away(), newAway, true);
            if (newAway >= normal && slot.death() != null) {
                entity.getEntityData().set(slot.death(), true);  // 血量耗尽 → 死亡标记
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
