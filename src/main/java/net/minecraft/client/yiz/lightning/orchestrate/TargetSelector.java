package net.minecraft.client.yiz.lightning.orchestrate;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * 目标选择策略 — Emitter 用它选要放电的目标。
 *
 * <p>先实现 {@link #nearby()}（够技能 A/C 用）。技能 B 的"准星 raycast 命中"在 LightningFX
 * 直接做（因需要视线方向，selector 接口不带方向）。</p>
 */
@FunctionalInterface
public interface TargetSelector {

    /** 以 origin 为中心、range 为半径，选最多 max 个目标实体。 */
    List<Entity> select(Level level, Vec3 origin, float range, int max);

    /** 附近最近的可拾取活实体（距离升序，取最近 max 个）。 */
    static TargetSelector nearby() {
        return (level, origin, range, max) -> {
            if (max <= 0) return List.of();
            AABB box = new AABB(
                    origin.x - range, origin.y - range, origin.z - range,
                    origin.x + range, origin.y + range, origin.z + range);
            List<Entity> all = level.getEntities((net.minecraft.world.entity.Entity) null, box, e -> e.isAlive() && e.isPickable());
            all.sort((a, b) -> Double.compare(a.distanceToSqr(origin), b.distanceToSqr(origin)));
            return all.size() > max ? new java.util.ArrayList<>(all.subList(0, max)) : all;
        };
    }
}
