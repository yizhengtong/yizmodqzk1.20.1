package net.minecraft.client.yiz.api;

import net.minecraft.client.yiz.attribute.YizAttributes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 投射物返还系统（1.21.1 移植版）。
 *
 * <p>下游注册反射条件，前置自动检测范围内的投射物并处理：
 * 无主人 → 移除，有主人 → 转移所有权并追踪原主人。</p>
 */
public final class ProjectileReflectionSystem {

    private static final List<ConfigEntry> CONFIGS = new CopyOnWriteArrayList<>();
    private static final Map<Projectile, Entity> TRACKED = new ConcurrentHashMap<>();

    private ProjectileReflectionSystem() {}

    public record ReflectionConfig(double range, int tickInterval, float speedMultiplier) {}

    @FunctionalInterface
    public interface Condition {
        boolean shouldReflect(Player player);
    }

    public static void register(Condition condition, ReflectionConfig config) {
        CONFIGS.add(new ConfigEntry(condition, config));
    }

    private record ConfigEntry(Condition condition, ReflectionConfig config) {}

    /**
     * 在投射物碰撞实体时调用（由 Mixin {@code ProjectileHitMixin} 注入）。
     * <p>
     * 检查目标是否配置了投射物反射，若是则：
     * <ul>
     *   <li>有主人 → 转移所有权，记录追踪，弹道重定向</li>
     *   <li>无主人 → 直接移除</li>
     * </ul>
     * 调用方应取消原碰撞事件。
     * </p>
     *
     * @param projectile 正在碰撞的投射物
     * @param hitEntity  被碰撞的实体
     * @return true 如果反射已处理（调用方应取消原碰撞事件）
     */
    public static boolean onProjectileHitEntity(Projectile projectile, Entity hitEntity) {
        if (!(hitEntity instanceof Player player)) return false;
        if (projectile.getOwner() == player) return false;

        double radius = player.getAttributeValue(
            YizAttributes.PROJECTILE_REFLECTION.get());
        if (radius <= 0) return false;

        return doReflect(projectile, player, 1.0F);
    }

    /** 执行反射逻辑（转移所有权 + 弹道重定向） */
    private static boolean doReflect(Projectile projectile, Player player, float speedMult) {
        Entity owner = projectile.getOwner();
        if (owner instanceof LivingEntity originalOwner) {
            projectile.setOwner(player);
            TRACKED.put(projectile, originalOwner);
            Vec3 dir = originalOwner.getEyePosition().subtract(projectile.position()).normalize();
            projectile.setDeltaMovement(dir.scale(speedMult));
            return true;
        } else {
            projectile.discard();
            return true;
        }
    }

    // ==================== 由下游事件或 Mixin 调用 ====================

    /**
     * 每 tick 调用（由 PlayerTickEvent.Post 或 Mixin 触发）。
     * 1. 扫描玩家范围内投射物
     * 2. 跟踪已反射的投射物朝向原主人
     */
    public static void tick(Player player) {
        if (player.level().isClientSide()) return;

        double radius = player.getAttributeValue(
            YizAttributes.PROJECTILE_REFLECTION.get());
        if (radius > 0) {
            scanAndReflect(player, radius, 1.0F);
        }

        updateTracked(player);
    }

    private static void scanAndReflect(Player player, double range, float speedMult) {
        AABB area = player.getBoundingBox().inflate(range);
        for (Entity e : player.level().getEntities(player, area,
                e2 -> e2 instanceof Projectile && e2.isAlive())) {

            Projectile projectile = (Projectile) e;
            if (projectile.getOwner() == player) continue;

            if (projectile.getOwner() instanceof LivingEntity originalOwner) {
                // 转移所有权
                projectile.setOwner(player);
                // 记录追踪：投射物 → 原主人
                TRACKED.put(projectile, originalOwner);
                // 初始弹道：朝原主人飞去
                Vec3 dir = originalOwner.getEyePosition().subtract(projectile.position()).normalize();
                projectile.setDeltaMovement(dir.scale(speedMult));
            } else {
                // 无主人（发射器等） → 移除
                projectile.discard();
            }
        }
    }

    private static void updateTracked(Player player) {
        Iterator<Map.Entry<Projectile, Entity>> it = TRACKED.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Projectile, Entity> entry = it.next();
            Projectile projectile = entry.getKey();
            Entity target = entry.getValue();

            if (!projectile.isAlive() || projectile.isRemoved()
                    || !target.isAlive() || target.isRemoved()) {
                it.remove();
                continue;
            }

            // 持续追踪：每 tick 微调弹道朝向原主人
            Vec3 dir = target.getEyePosition().subtract(projectile.position()).normalize();
            double currentSpeed = projectile.getDeltaMovement().length();
            projectile.setDeltaMovement(dir.scale(currentSpeed));
        }
    }
}
