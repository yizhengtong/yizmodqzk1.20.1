package net.minecraft.client.yiz.effect;

import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.LivingEntity;

/**
 * 流血效果（纯前端展示）：
 * 只作为 HUD 图标 + 持续时间显示（与 {@code BleedSystem} 的流血状态同步），
 * 不驱动任何实际效果——流血伤害由 {@code BleedSystem} 独立结算（分 4 次持续，无视减免）。
 * 实体被直接挂上此效果不会触发流血；只有真正进入流血状态才会被添加上。
 * 图标默认从 {@code textures/mob_effect/bleed.png} 自动加载。
 */
public class BleedEffect extends MobEffect {

    public BleedEffect() {
        super(MobEffectCategory.HARMFUL, 0xCC2222);
    }

    /** 纯展示：不驱动逻辑。 */
    @Override
    public void applyEffectTick(LivingEntity entity, int amplifier) {}

    /** 不每 tick 触发效果。 */
    @Override
    public boolean isDurationEffectTick(int duration, int amplifier) {
        return false;
    }
}
