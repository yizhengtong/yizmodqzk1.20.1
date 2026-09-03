package net.minecraft.client.yiz.handler;

import net.minecraft.client.yiz.itemcfg.FeatureType;
import net.minecraft.client.yiz.itemcfg.ItemConfigGates;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import top.theillusivec4.curios.api.event.CurioEquipEvent;

/**
 * Curios 饰品装备拦截（万能物品配置）。
 *
 * <p>玩家关闭了某物品的 CURIOS_SLOT（饰品槽位效果/整体禁用）→ {@link CurioEquipEvent} cancel，
 * 该玩家无法把该物品装备到 Curios 饰品槽。per-player、通用。</p>
 *
 * <p>持续效果（curioTick）的关闭走全局 VTable 废除（ItemConfigAbolition + CurioMethodsDonor），
 * 属性加成走 CurioAttributeModifierEvent（CuriosAttributeHandler），均不在此。</p>
 *
 * <p>仅在 Curios 加载时由 tizMod 注册（未装 Curios 不注册本类，避免 NoClassDefFound）。</p>
 */
public final class CuriosEquipHandler {

    private CuriosEquipHandler() {}

    @SubscribeEvent
    public static void onCurioEquip(CurioEquipEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        if (sp.level().isClientSide()) return;
        if (ItemConfigGates.isDisabled(sp, event.getStack(), FeatureType.CURIOS_SLOT)) {
            event.setCanceled(true);
        }
    }
}
