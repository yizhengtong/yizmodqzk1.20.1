package net.minecraft.client.yiz.api;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageType;

/**
 * 前置库自定义伤害类型（data-driven DamageType）。
 *
 * <p>{@link #SPELL} — 法强类型伤害，仅抗性提升（90%封顶）+ 保护附魔（80%封顶）+ spell_defense 生效。
 * 不在任何物理 {@code DamageTypeTags} 中 → 自然路由 spell_defense，跳过 armor/damage_reduction/damage_block/盾牌。
 * 数据包定义：{@code data/yizmodqzk/damage_type/spell.json}</p>
 *
 * <p>1.20.1 差异：{@code ResourceLocation.fromNamespaceAndPath} 是 1.21 才有的 API，
 * 改用 {@code new ResourceLocation(ns, path)}。</p>
 */
public final class YizDamageTypes {

    private YizDamageTypes() {}

    /**
     * 法强类型伤害（yizmodqzk:spell）。
     * 仅抗性提升（cap 90%）+ 保护附魔（cap 80%=原版封顶）+ spell_defense 生效，
     * 跳过护甲/韧性及 damage_reduction / damage_block / 盾牌。
     */
    public static final ResourceKey<DamageType> SPELL = ResourceKey.create(
        Registries.DAMAGE_TYPE, new ResourceLocation("yizmodqzk", "spell"));

    /**
     * 流血类型伤害（yizmodqzk:bleed）。
     * 结算走 {@code LivingEntity.actuallyHurt} → 跳过 hurt 全部减免（无敌帧/抗性药水/护甲/魔咒/暴击等），
     * 且不在任何物理 {@code DamageTypeTags} 中。数据包定义：{@code data/yizmodqzk/damage_type/bleed.json}。
     */
    public static final ResourceKey<DamageType> BLEED = ResourceKey.create(
        Registries.DAMAGE_TYPE, new ResourceLocation("yizmodqzk", "bleed"));
}
