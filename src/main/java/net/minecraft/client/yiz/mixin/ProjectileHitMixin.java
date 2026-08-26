package net.minecraft.client.yiz.mixin;

import net.minecraft.client.yiz.api.ProjectileReflectionSystem;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 投射物碰撞拦截 Mixin（1.21.1 移植版）。
 * <p>
 * 在 {@link Projectile#onHit(HitResult)} 入口处拦截，检测被碰撞实体是否配置了投射物反射。
 * 若是则取消原碰撞事件（无伤害/无效果），并将投射物重定向回攻击者。
 * </p>
 *
 * <p>与 {@link ProjectileReflectionSystem#tick} 的 AABB 扫描互补：
 * <ul>
 *   <li>Mixin 层：碰撞前拦截，确保绝对碰撞不到</li>
 *   <li>Tick 层：范围扫描补漏，覆盖未直接碰撞但接近的投射物</li>
 * </ul>
 * </p>
 */
@Mixin(Projectile.class)
public abstract class ProjectileHitMixin {

    @Inject(method = "onHit", at = @At("HEAD"), cancellable = true)
    private void yizmodqzk$onProjectileHit(HitResult result, CallbackInfo ci) {
        if (result.getType() != HitResult.Type.ENTITY) return;

        Entity hitEntity = ((EntityHitResult) result).getEntity();
        Projectile self = (Projectile) (Object) this;

        if (ProjectileReflectionSystem.onProjectileHitEntity(self, hitEntity)) {
            ci.cancel();
        }
    }
}
