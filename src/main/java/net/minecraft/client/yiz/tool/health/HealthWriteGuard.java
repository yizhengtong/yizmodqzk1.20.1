package net.minecraft.client.yiz.tool.health;

import net.minecraft.world.entity.LivingEntity;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 健康值字段写入守卫（1.20.1 精简版）。
 *
 * <p>辖界者走 {@link SecureHealthClosure} 外部表血量（getHealth 读表），vanilla {@code health}
 * 字段不参与逻辑血量——外部模组反射直写该字段不影响 getHealth。本守卫的职责简化为：
 * 每 tick 用 {@link EntityActuallyHurt#catchSetTrueHealth} 把 vanilla health 字段/DataParameter
 * 纠正回外部表真值（防外部模组读 DataParameter 得到假 0 触发死亡判定）。</p>
 */
public final class HealthWriteGuard {

    private HealthWriteGuard() {}

    private static final Map<UUID, Boolean> REGISTERED = new ConcurrentHashMap<>();

    /** 登记受管理实体（applyEntityAttributes 第一 tick 调用）。 */
    public static void register(LivingEntity entity) {
        if (entity == null || entity.level().isClientSide()) return;
        REGISTERED.put(entity.getUUID(), Boolean.TRUE);
    }

    /** 注销实体（死亡/卸载清理）。 */
    public static void remove(LivingEntity entity) {
        REGISTERED.remove(entity.getUUID());
    }

    /** 每 tick：把 vanilla health 字段/DataParameter 纠正回外部表真值。 */
    public static void enforce(LivingEntity entity) {
        if (!REGISTERED.containsKey(entity.getUUID())) return;
        if (!SecureHealthClosure.isRegistered(entity)) return;
        float real = SecureHealthClosure.getHealth(entity);
        if (real > 0 && Math.abs(entity.getHealth() - real) > 0.01f) {
            EntityActuallyHurt.catchSetTrueHealth(entity, real);
        }
    }

    /** 更新基线（本模组主动扣血后调用，简化版无独立基线，直接以表值为准）。 */
    public static void updateBaseline(LivingEntity entity) {
        // 外部表模式下无独立基线，忽略
    }
}
