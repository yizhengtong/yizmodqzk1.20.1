package net.minecraft.client.yiz.mixin;

import net.minecraft.world.effect.MobEffectInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 暴露 {@code MobEffectInstance.duration}（private）：
 * 流血展示 Buff 同步真实剩余时间（addEffect 的 update 只取更大值，无法递减）。
 */
@Mixin(MobEffectInstance.class)
public interface MobEffectInstanceDurationAccessor {

    @Accessor("duration")
    void yizmodqzk$setDuration(int duration);
}
