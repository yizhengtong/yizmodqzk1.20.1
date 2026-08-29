package net.minecraft.client.yiz.effect;

import net.minecraft.world.effect.MobEffect;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * 流血效果注册（前端展示用 Buff，图标 assets/yizmodqzk/textures/mob_effect/bleed.png）。
 */
public final class BleedEffects {

    public static final DeferredRegister<MobEffect> EFFECTS =
        DeferredRegister.create(ForgeRegistries.MOB_EFFECTS, "yizmodqzk");

    public static final RegistryObject<MobEffect> BLEED =
        EFFECTS.register("bleed", BleedEffect::new);

    private BleedEffects() {}
}
