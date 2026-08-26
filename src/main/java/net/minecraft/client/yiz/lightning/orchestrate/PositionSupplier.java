package net.minecraft.client.yiz.lightning.orchestrate;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * 位置提供者 — 统一所有"位置"来源（电弧端点、Emitter 源、球位置都可绑它）。
 *
 * <p>四类来源通过静态工厂组合：固定坐标 / 跟随实体 / 跟随球 / 叠加偏移。
 * 例如「玩家头顶」= {@code offset(following(player), 0, height+1, 0)}。</p>
 */
@FunctionalInterface
public interface PositionSupplier {
    Vec3 get(float partialTick);

    /** 固定世界坐标。 */
    static PositionSupplier fixed(Vec3 pos) {
        return pt -> pos;
    }

    /** 跟随实体（partialTick 帧间插值位置 → 平滑跟随，不随 tick 抖动/滞后）。 */
    static PositionSupplier following(Entity entity) {
        return pt -> new Vec3(
                entity.xOld + (entity.getX() - entity.xOld) * pt,
                entity.yOld + (entity.getY() - entity.yOld) * pt,
                entity.zOld + (entity.getZ() - entity.zOld) * pt);
    }

    /** 跟随另一个 supplier（如球的 PositionSupplier）。 */
    static PositionSupplier following(PositionSupplier base) {
        return base;
    }

    /** 在 base 上叠加固定偏移。 */
    static PositionSupplier offset(PositionSupplier base, double x, double y, double z) {
        Vec3 off = new Vec3(x, y, z);
        return pt -> base.get(pt).add(off);
    }

    /** 飞行：从 start 沿 velocity（格/秒）直线移动，基于墙钟（平滑，不受 tick 抖动影响）。 */
    static PositionSupplier flying(Vec3 start, Vec3 velocity) {
        long t0 = System.currentTimeMillis();
        return pt -> start.add(velocity.scale((System.currentTimeMillis() - t0) / 1000.0));
    }
}
