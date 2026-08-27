package net.minecraft.client.yiz.tool.attribute;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * 穿戴物品 NBT 属性聚合器（方案 A）— 每 tick 把玩家穿戴槽物品的 NBT 属性累加到实体
 * AttributeInstance（幂等 modifier）。属性消费点读实体属性 → 生效；物品无原版 modifier →
 * tooltip 不显示槽位行。
 *
 * <p>聚合范围：玩家 6 个穿戴槽（主手/副手/头/胸/腿/脚）。背包物品不生效。
 * 值为 0 时 setEntityAttribute 会移除 modifier（改 0 = 清除属性）。</p>
 */
public final class NbtAttributeAggregator {

    private NbtAttributeAggregator() {}

    /** 诊断：记录已打印过一次的非零聚合属性。 */
    private static final java.util.Set<String> DIAG_LOGGED = new java.util.HashSet<>();

    /** 每 tick 聚合（由 tizMod.onPlayerTick 服务端分支调用）。 */
    public static void aggregate(Player player) {
        // 服务端权威（tizMod.onPlayerTick）；客户端也聚合本地玩家（LockOnProvider 渲染读 HUIXIN/KEGONG，
        // 需要客户端属性值；客户端本地加 modifier 不回传服务端，安全）。1.20.1 单机/联机都适用。
        for (net.minecraft.client.yiz.editor.EditableAttribute attr
                : net.minecraft.client.yiz.editor.EditableAttribute.getAll()) {
            Attribute attribute = resolveAttribute(attr.id());
            if (attribute == null) continue; // 无对应属性（max_durability 等特殊项）

            double total = 0;
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                ItemStack stack = player.getItemBySlot(slot);
                if (!stack.isEmpty()) {
                    total += NbtAttributeHelper.get(stack, attr.id());
                }
            }
            ItemAttributeHandler.setEntityAttribute(player, attribute,
                "yiz_nbt_" + attr.id(), total, AttributeModifier.Operation.ADDITION);
            if (total > 0 && !DIAG_LOGGED.contains(attr.id())) {
                DIAG_LOGGED.add(attr.id());
                System.out.println("[NbtAgg] " + attr.id() + " = " + total);
            }
        }

        // ATTACK_RANGE 镜像到原版触及距离（BLOCK_REACH/ENTITY_REACH）——服务端距离验证
        // （canReach 方块破坏 / canReachRaw 实体攻击）读这两个属性而非 ATTACK_RANGE，不镜像则无效。
        var atkRange = player.getAttribute(net.minecraft.client.yiz.attribute.YizAttributes.ATTACK_RANGE.get());
        double range = atkRange != null ? atkRange.getValue() : 0;
        ItemAttributeHandler.setEntityAttribute(player, net.minecraftforge.common.ForgeMod.BLOCK_REACH.get(),
                "yiz_attack_range_block", range, AttributeModifier.Operation.ADDITION);
        ItemAttributeHandler.setEntityAttribute(player, net.minecraftforge.common.ForgeMod.ENTITY_REACH.get(),
                "yiz_attack_range_entity", range, AttributeModifier.Operation.ADDITION);

        // 护甲/法防镜像到原版：自定义护甲值提供原版护甲+韧性（护甲条/原版护甲公式）；法防镜像击退韧性。
        // 模组指数减伤仍读自定义属性（不受原版护甲值变动影响）。
        net.minecraft.client.yiz.tizMod.mirrorArmor(player);
        net.minecraft.client.yiz.tizMod.mirrorSpellDefense(player);
    }

    /** 从属性 id 解析 Attribute（generic.* → minecraft，否则 yizmodqzk）。 */
    private static Attribute resolveAttribute(String id) {
        if (id == null) return null;
        if (id.startsWith("generic.")) {
            return ForgeRegistries.ATTRIBUTES.getValue(new ResourceLocation("minecraft", id));
        }
        return ForgeRegistries.ATTRIBUTES.getValue(new ResourceLocation("yizmodqzk", id));
    }
}
