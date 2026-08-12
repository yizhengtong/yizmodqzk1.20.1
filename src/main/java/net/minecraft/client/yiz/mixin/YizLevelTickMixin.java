package net.minecraft.client.yiz.mixin;

import net.minecraft.client.yiz.bridge.YizTickTracker;
import net.minecraft.client.yiz.tool.health.EntityASMUtil;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Consumer;

/**
 * guardEntityTick 防重复：被强制双 tick 的实体（lastTickCount == tickCount）已 tick 过，
 * vanilla 的普通 tick 调用在此取消，避免同一 tick 内被普通流程再 tick 一次。
 */
@Mixin(value = Level.class, priority = Integer.MAX_VALUE)
public abstract class YizLevelTickMixin {

    @Inject(method = "guardEntityTick", at = @At("HEAD"), cancellable = true)
    private <T extends Entity> void yizmodqzk$guard(Consumer<T> consumer, T entity, CallbackInfo ci) {
        if (EntityASMUtil.shouldOverrideTick(entity) && entity instanceof YizTickTracker t
                && t.yizmodqzk$getLastTickCount() == entity.tickCount) {
            ci.cancel();
        }
    }
}
