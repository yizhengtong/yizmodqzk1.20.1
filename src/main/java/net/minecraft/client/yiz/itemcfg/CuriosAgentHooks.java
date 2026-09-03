package net.minecraft.client.yiz.itemcfg;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.SlotContext;

import java.util.Optional;

/**
 * agent 注入 curioTick 调用点的判定钩子（被 CuriosTickTransformer 的 ASM 字节码 INVOKESTATIC 调用）。
 *
 * <p>玩家关闭了某饰品的 CURIO_TICK → 返回 true，跳过该饰品的 {@code ICurio.curioTick}
 * （饰品留在槽里但每 tick 效果停，per-player 精确）。</p>
 */
public final class CuriosAgentHooks {

    private CuriosAgentHooks() {}

    public static boolean shouldSkipCurioTick(SlotContext ctx) {
        LivingEntity entity = ctx.entity();
        if (!(entity instanceof ServerPlayer sp)) return false;
        if (sp.level().isClientSide()) return false;
        ItemStack stack = CuriosApi.getCuriosInventory(sp)
            .map(h -> h.getStacksHandler(ctx.identifier()))
            .filter(Optional::isPresent)
            .map(o -> o.get().getStacks().getStackInSlot(ctx.index()))
            .orElse(ItemStack.EMPTY);
        return !stack.isEmpty() && ItemConfigGates.isDisabled(sp, stack, FeatureType.CURIO_TICK);
    }
}
