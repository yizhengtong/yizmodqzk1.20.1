package net.minecraft.client.yiz.tool;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/**
 * Yiz 实体管理器 — 通用实体生命周期管理入口（1.20.1 移植版）。
 */
public final class YizieManager {

    private YizieManager() {}

    /** 检测实体生命值 ≤0 → 走原版移除链移除。 */
    public static boolean checkAndRemove(Entity entity) {
        if (!(entity instanceof LivingEntity living)) return false;
        if (living.isRemoved()) return false;
        if (living.level().isClientSide()) return false;
        if (living.getHealth() > 0.0F) return false;

        living.remove(Entity.RemovalReason.KILLED);
        return true;
    }

    /** 只检测是否「该移除」。 */
    public static boolean shouldRemove(LivingEntity entity) {
        return entity != null && !entity.isRemoved()
                && !entity.level().isClientSide()
                && entity.getHealth() <= 0.0F;
    }
}
