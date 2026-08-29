package net.minecraft.client.yiz.mixin;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 暴露 {@code LivingEntity.actuallyHurt}（protected）：
 * 流血伤害直接结算，跳过 hurt 的全部减免（无敌帧/抗性药水/护甲/魔咒）。
 */
@Mixin(LivingEntity.class)
public interface LivingEntityActuallyHurtAccessor {

    @Invoker("actuallyHurt")
    void yizmodqzk$actuallyHurt(DamageSource source, float amount);
}
