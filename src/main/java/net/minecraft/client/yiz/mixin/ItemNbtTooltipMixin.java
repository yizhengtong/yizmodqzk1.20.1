package net.minecraft.client.yiz.mixin;

import net.minecraft.client.yiz.editor.EditableAttribute;
import net.minecraft.client.yiz.tool.attribute.NbtAttributeHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * 物品 NBT 属性 tooltip（方案 A）— 在物品 tooltip 追加"提供属性"行。
 *
 * <p>工作方块编辑的属性存物品 NBT（yizmodqzk:attrs），无原版 AttributeModifier →
 * 不显示"穿戴于头/胸/腿/脚/主手/副手"槽位行；本 Mixin 读 NBT 追加一行"提供属性"显示。</p>
 */
@Mixin(Item.class)
public abstract class ItemNbtTooltipMixin {

    @Inject(method = "appendHoverText", at = @At("HEAD"))
    private void yizmodqzk$appendNbtAttrs(ItemStack stack, @Nullable Level level,
                                          List<Component> tooltip, TooltipFlag flag, CallbackInfo ci) {
        if (!NbtAttributeHelper.hasAny(stack)) return;
        CompoundTag attrs = stack.getTag().getCompound(NbtAttributeHelper.ATTRS_KEY);
        if (attrs.isEmpty()) return;

        // 追加一行"提供属性"
        tooltip.add(Component.literal("§7提供属性："));
        for (String key : attrs.getAllKeys()) {
            double v = attrs.getDouble(key);
            if (v == 0) continue;
            EditableAttribute attr = EditableAttribute.getAll().stream()
                .filter(a -> a.id().equals(key))
                .findFirst().orElse(null);
            if (attr != null) {
                tooltip.add(Component.literal(" §f" + attr.listLabel(v)));
            }
        }
    }
}
