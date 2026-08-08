package net.minecraft.client.yiz.api;

import net.minecraft.client.yiz.editor.EnhanceTagRegistry;
import net.minecraft.client.yiz.editor.SkillConfigStorage;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * transient 效果的「来源校验」工具（主动兜底层）。
 *
 * <p>用于在 transient 效果 tick/生效时，校验其来源（被动/技能/标签）是否仍在装配槽。
 * 若来源已不在（被卸载、死亡掉落、物品销毁），效果应自清——这是 {@code EffectRegistry}
 * 钩子清理之外的兜底，覆盖非界面卸载路径。</p>
 *
 * <p>注册名比较用 {@code namespace:path} 全串（如 {@code yizxianmod:tianleiyin}）。</p>
 */
public final class EffectSources {

    private EffectSources() {}

    /** 被动物品是否还在玩家的被动槽（×3）。regName 形如 "yizxianmod:tianleiyin"。 */
    public static boolean passiveEquipped(ServerPlayer player, String regName) {
        SkillConfigStorage.Data data = SkillConfigStorage.get(player.getUUID());
        if (data == null) return false;
        for (int i = 0; i < 3; i++) {
            if (matchesRegName(data.passiveLoad().getItem(i), regName)) return true;
        }
        return false;
    }

    /** 主动技能是否还在玩家的大槽或技能槽（0=大槽,1-3=技能槽）。 */
    public static boolean skillEquipped(ServerPlayer player, String regName) {
        SkillConfigStorage.Data data = SkillConfigStorage.get(player.getUUID());
        if (data == null) return false;
        if (matchesRegName(data.bigLoad().getItem(0), regName)) return true;
        for (int i = 0; i < 3; i++) {
            if (matchesRegName(data.skillLoad().getItem(i), regName)) return true;
        }
        return false;
    }

    /** 标签是否还激活（提供者被动仍在 + 强化等级>0）。复用 EnhanceTagRegistry 逻辑。 */
    public static boolean tagActive(ServerPlayer player, String tagKey) {
        return EnhanceTagRegistry.isTagActive(player, tagKey);
    }

    /** ItemStack 的注册名是否等于给定 regName（空物品不匹配）。 */
    private static boolean matchesRegName(ItemStack stack, String regName) {
        if (stack.isEmpty()) return false;
        var key = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return key != null && key.toString().equals(regName);
    }

    /** 取物品注册名（供 SkillConfigMenu 构造来源 key 用）。 */
    public static String regNameOf(ItemStack stack) {
        if (stack.isEmpty()) return "";
        var key = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return key != null ? key.toString() : "";
    }
}
