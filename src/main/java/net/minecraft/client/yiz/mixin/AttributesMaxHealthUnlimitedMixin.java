package net.minecraft.client.yiz.mixin;

import net.minecraft.world.entity.ai.attributes.Attributes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/**
 * 放宽原版属性上限 1024 → 无上限（自走棋高星棋子血量可达 2400+，原版 RangedAttribute 1024 上限
 * 会让 setBaseValue clamp 封顶，星级血量放大失效）。只改上限值，默认值不受影响。
 *
 * <p>注：{@code <clinit>} 里多个属性上限为 1024.0D（MAX_HEALTH/MOVEMENT_SPEED/FLYING_SPEED/ATTACK_SPEED/LUCK），
 * 统一放宽无害（仅放宽上限，不改默认值）。</p>
 */
@Mixin(Attributes.class)
public abstract class AttributesMaxHealthUnlimitedMixin {

    @ModifyConstant(method = "<clinit>", constant = @Constant(doubleValue = 1024.0D))
    private static double yizqzk$unlimitAttributeCap(double original) {
        return Double.MAX_VALUE;
    }
}
