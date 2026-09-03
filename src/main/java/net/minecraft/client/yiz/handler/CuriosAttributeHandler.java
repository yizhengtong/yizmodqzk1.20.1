package net.minecraft.client.yiz.handler;

import net.minecraft.client.yiz.itemcfg.FeatureType;
import net.minecraft.client.yiz.itemcfg.ItemConfigGates;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import top.theillusivec4.curios.api.event.CurioAttributeModifierEvent;

/**
 * 饰品属性加成拦截（per-player 精确）。
 * 玩家关闭了某饰品的 CURIO_ATTRIBUTES → {@link CurioAttributeModifierEvent} clearModifiers，
 * 该玩家装备该饰品时无属性加成。
 */
public final class CuriosAttributeHandler {

    private CuriosAttributeHandler() {}

    @SubscribeEvent
    public static void onCurioAttribute(CurioAttributeModifierEvent event) {
        LivingEntity entity = event.getSlotContext().entity();
        if (!(entity instanceof ServerPlayer sp)) return;
        if (sp.level().isClientSide()) return;
        if (ItemConfigGates.isDisabled(sp, event.getItemStack(), FeatureType.CURIO_ATTRIBUTES)) {
            event.clearModifiers();
        }
    }
}
