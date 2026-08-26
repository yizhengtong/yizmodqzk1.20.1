package net.minecraft.client.yiz.handler;

import net.minecraft.client.yiz.attribute.YizAttributes;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Tier;
import net.minecraft.world.item.Tiers;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.TierSortingRegistry;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.registries.RegistryObject;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 挖掘属性事件处理 — 消费 7 个挖掘属性（1.21.1 PlayerMiningMixin 移植版）。
 *
 * <p>1.20.1 用 Forge 标准事件替代 1.21.1 的 getDigSpeed/hasCorrectToolForDrops mixin 注入：
 * <ul>
 *   <li>{@link PlayerEvent.BreakSpeed} — 免疫惩罚 + 类型覆盖 + 效率（原 getDigSpeed 逻辑）</li>
 *   <li>{@link PlayerEvent.HarvestCheck} — 挖掘等级覆盖掉落判定（原 hasCorrectToolForDrops 逻辑）</li>
 * </ul>
 * 注入目标方法在 1.20.1 obf 映射缺失（getDigSpeed 无法定位），故改用事件（语义等价）。
 * 支持任意手持物品（含空手）。</p>
 */
public final class MiningAttributeHandler {

    private MiningAttributeHandler() {}

    private static double attr(Player p, RegistryObject<Attribute> a) {
        if (a == null || !a.isPresent()) return 0;
        var inst = p.getAttribute(a.get());
        return inst != null ? inst.getValue() : 0;
    }

    /** 任意一个挖掘类型属性覆盖此方块即返回 true。挖掘类：全 覆盖一切。 */
    private static boolean typeCovers(Player p, BlockState state) {
        if (attr(p, YizAttributes.MINING_PICKAXE) >= 1 && state.is(BlockTags.MINEABLE_WITH_PICKAXE)) return true;
        if (attr(p, YizAttributes.MINING_AXE) >= 1 && state.is(BlockTags.MINEABLE_WITH_AXE)) return true;
        if (attr(p, YizAttributes.MINING_SHOVEL) >= 1 && state.is(BlockTags.MINEABLE_WITH_SHOVEL)) return true;
        if (attr(p, YizAttributes.MINING_ALL) >= 1) return true;
        return false;
    }

    /**
     * 返回挖掘该方块所需最低 Tier：0=木, 1=石, 2=铁, 3=钻石, 4=下界合金, 5=不可挖掘。
     * <p>1.20.1 无 INCORRECT_FOR_* BlockTags 常量，改用 {@link TierSortingRegistry#isCorrectTierForDrops}
     * 遍历标准 Tier 找最低可挖等级（1.20.1 标准判定方式）。</p>
     */
    private static int getRequiredHarvestTier(BlockState state) {
        if (!state.requiresCorrectToolForDrops()) return 0;
        Tier[] ordered = {Tiers.WOOD, Tiers.STONE, Tiers.IRON, Tiers.DIAMOND, Tiers.NETHERITE};
        for (int i = 0; i < ordered.length; i++) {
            if (TierSortingRegistry.isCorrectTierForDrops(ordered[i], state)) {
                return i;
            }
        }
        return 5;
    }

    /** BreakSpeed：免疫惩罚 + 类型覆盖 + 效率。 */
    @SubscribeEvent
    public static void onBreakSpeed(PlayerEvent.BreakSpeed event) {
        Player player = event.getEntity();
        if (player == null) return;
        BlockState state = event.getState();
        float speed = event.getOriginalSpeed();

        // ── 免疫挖掘惩罚：逆转空中/水中/挖掘疲劳的全部负面效果 ──
        if (attr(player, YizAttributes.MINING_PENALTY_IMMUNITY) >= 1) {
            // 逆转空中减速（原版 /= 5.0F）
            if (!player.onGround()) speed *= 5.0F;
            // 逆转水中减速（1.20.1 无 SUBMERGED_MINING_SPEED 属性，vanilla 硬编码 ×5，等价还原 ÷5）
            if (player.isEyeInFluid(FluidTags.WATER)) {
                speed /= 5.0F;
            }
            // 逆转挖掘疲劳
            if (player.hasEffect(MobEffects.DIG_SLOWDOWN)) {
                int amp = player.getEffect(MobEffects.DIG_SLOWDOWN).getAmplifier();
                float fatigueMult = switch (amp) {
                    case 0 -> 0.3F;
                    case 1 -> 0.09F;
                    case 2 -> 0.0027F;
                    default -> 8.1E-4F;
                };
                speed /= fatigueMult;
            }
        }

        // ── 挖掘类型覆盖：手持错误工具时给合理基础速度 ──
        float itemBase = player.getInventory().getDestroySpeed(state);
        if (itemBase <= 1.0f && typeCovers(player, state)) {
            int level = (int) attr(player, YizAttributes.MINING_LEVEL);
            float base = 2.0f + level * 2.0f; // lv0=2, lv1=4, lv2=6, lv3=8, lv4=10
            if (speed < base) speed = base;
        }

        // ── 挖掘效率固定加成 ──
        double eff = attr(player, YizAttributes.MINING_EFFICIENCY);
        if (eff > 0) speed *= (float) (1.0 + eff / 100.0);

        if (speed != event.getOriginalSpeed()) {
            event.setNewSpeed(speed);
        }
    }

    /** HarvestCheck：挖掘等级覆盖掉落判定。 */
    @SubscribeEvent
    public static void onHarvestCheck(PlayerEvent.HarvestCheck event) {
        if (event.canHarvest()) return;
        Player player = event.getEntity();
        if (player == null) return;
        int level = (int) attr(player, YizAttributes.MINING_LEVEL);
        if (level <= 0) return;
        if (level >= getRequiredHarvestTier(event.getTargetBlock())) {
            event.setCanHarvest(true);
        }
    }
}
