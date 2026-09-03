package net.minecraft.client.yiz.itemcfg;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 门控查询唯一入口（mixin 调用）+ per-player 语义功能激活缓存。
 *
 * <p>结构功能直接查 {@link ConfigRegistry}；语义功能（仇恨免疫/取消受击/增伤）由
 * 本系统统一施加——玩家持有 adapter 声明的物品且功能未关闭 → 缓存"激活"状态，
 * 门控 mixin 在 vanilla 链上施加效果（Mob.setTarget cancel / hurt false / 伤害放大）。
 * 关闭开关 → 缓存失效 → 本系统不再施加，恢复 vanilla。</p>
 */
public final class ItemConfigGates {

    private static final int RESCAN_INTERVAL = 20;

    /** 玩家 → 已激活的语义功能 id 集合。 */
    private static final Map<UUID, Set<String>> ACTIVE_SEMANTIC = new ConcurrentHashMap<>();
    /** 玩家 → 语义功能因子（DAMAGE_BOOST）。 */
    private static final Map<UUID, Map<String, Float>> ACTIVE_FACTORS = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> TICK_COUNTER = new ConcurrentHashMap<>();

    private ItemConfigGates() {}

    // ══════════════════════════════════════════════════════════
    //  结构功能门控
    // ══════════════════════════════════════════════════════════

    public static boolean isDisabled(ServerPlayer player, ItemStack stack, FeatureType ft) {
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (id == null) return false;
        return ConfigRegistry.isDisabled(player.getUUID(), id, ft.name());
    }

    public static boolean isDisabled(ServerPlayer player, ResourceLocation itemId, String feature) {
        return ConfigRegistry.isDisabled(player.getUUID(), itemId, feature);
    }

    // ══════════════════════════════════════════════════════════
    //  语义功能激活缓存
    // ══════════════════════════════════════════════════════════

    /** 玩家当前是否"激活"了某语义功能（持有相关物品且未关闭）。 */
    public static boolean hasActiveSemantic(ServerPlayer player, String featureId) {
        return ACTIVE_SEMANTIC.getOrDefault(player.getUUID(), Set.of()).contains(featureId);
    }

    /** 语义功能因子（DAMAGE_BOOST），未激活返回 0。 */
    public static float semanticFactor(ServerPlayer player, String featureId) {
        return ACTIVE_FACTORS.getOrDefault(player.getUUID(), Map.of()).getOrDefault(featureId, 0f);
    }

    /** 配置变更后立即失效缓存。 */
    public static void invalidate(UUID uuid) {
        ACTIVE_SEMANTIC.remove(uuid);
        ACTIVE_FACTORS.remove(uuid);
        TICK_COUNTER.remove(uuid);
    }

    /** 服务端每 tick 驱动：周期重算语义激活缓存。 */
    public static void onServerTick(ServerPlayer sp) {
        int c = TICK_COUNTER.merge(sp.getUUID(), 1, Integer::sum);
        if (c >= RESCAN_INTERVAL) {
            TICK_COUNTER.put(sp.getUUID(), 0);
            rescan(sp);
        }
    }

    /** 遍历玩家物品栏/穿戴：收集"持有且未关闭"的语义功能。 */
    private static void rescan(ServerPlayer sp) {
        Set<String> active = new HashSet<>();
        Map<String, Float> factors = new HashMap<>();
        var inventory = sp.getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.isEmpty()) continue;
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
            if (id == null) continue;
            for (AdapterRegistry.AdapterFeature af : AdapterRegistry.find(id)) {
                // 功能未关闭 → 本系统施加语义效果
                if (ConfigRegistry.isDisabled(sp.getUUID(), id, af.feature())) continue;
                active.add(af.feature());
                if ("DAMAGE_BOOST".equalsIgnoreCase(af.gate()) && af.extra().containsKey("factor")) {
                    try {
                        factors.put(af.feature(), Float.parseFloat(af.extra().get("factor")));
                    } catch (NumberFormatException ignored) {}
                }
            }
        }
        ACTIVE_SEMANTIC.put(sp.getUUID(), active);
        if (factors.isEmpty()) ACTIVE_FACTORS.remove(sp.getUUID());
        else ACTIVE_FACTORS.put(sp.getUUID(), factors);
    }
}
