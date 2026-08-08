package net.minecraft.client.yiz.editor;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 技能配置界面容器的服务端存储（v2: 430×200 新布局）。
 * <p>内存容器 + PlayerDataAPI 持久化。</p>
 *
 * <p>1.20.1 移植：
 * <ul>
 *   <li>DataComponent（CUSTOM_DATA）→ 1.20.1 的 ItemStack NBT（getTag/getOrCreateTag）。</li>
 *   <li>{@code ItemStack.save(registryAccess)}/{@code ItemStack.parse} → {@code save(new CompoundTag())}/{@code ItemStack.of}。</li>
 *   <li>PlayerDataAPI（1.21.1，未移植）→ {@link #savePlayerData}/{@link #loadPlayerData} TODO 占位（暂不持久化）。</li>
 * </ul></p>
 */
public final class SkillConfigStorage {

    private SkillConfigStorage() {}

    public record Data(
        SimpleContainer skillUpgrade,   // B: 技能升级槽 ×1
        SimpleContainer bigLoad,        // F: 大装载槽 ×1
        SimpleContainer skillLoad,      // F: 技能装载槽 ×3
        SimpleContainer passiveLoad,    // F: 被动装载槽 ×3
        SimpleContainer skillLibrary,   // G: 技能库 ×20
        SimpleContainer equipment       // H: 装备槽 ×6
    ) {}

    private static final String PERSIST_KEY = "yizmodqzk:skill_config_slots";
    private static final Map<UUID, Data> STORE = new ConcurrentHashMap<>();
    private static final String ENHANCE_NBT_KEY = "yiz:enhance";

    /** 从物品 NBT 读取加强等级。每物品实例独立。 */
    public static int[] getEnhanceLevels(ItemStack stack) {
        if (stack.isEmpty()) return new int[6];
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(ENHANCE_NBT_KEY)) return new int[6];
        return tag.getIntArray(ENHANCE_NBT_KEY);
    }

    /** 写入物品 NBT：设置单个槽位加强等级。 */
    public static void setEnhanceLevel(ItemStack stack, int slot, int level) {
        if (stack.isEmpty()) return;
        int[] levels = getEnhanceLevels(stack);
        if (levels.length < 6) levels = new int[6];
        if (slot >= 0 && slot < levels.length) levels[slot] = Math.max(0, level);
        stack.getOrCreateTag().putIntArray(ENHANCE_NBT_KEY, levels);
    }

    /** 兼容旧 API：通过 ItemStack 查等级。 */
    public static int getEnhanceLevel(ItemStack stack, int slot) {
        int[] levels = getEnhanceLevels(stack);
        return slot >= 0 && slot < levels.length ? levels[slot] : 0;
    }

    public static Data getOrCreate(UUID playerId) {
        return STORE.computeIfAbsent(playerId, k -> new Data(
            new SimpleContainer(1), new SimpleContainer(1),
            new SimpleContainer(3), new SimpleContainer(3),
            new SimpleContainer(20), new SimpleContainer(6)));
    }

    public static Data get(UUID playerId) { return STORE.get(playerId); }

    /** 清空指定玩家的内存数据（登出/切存档时调用，防止跨存档泄漏）。 */
    public static void clear(UUID playerId) { STORE.remove(playerId); }

    /** 清空全部（全服玩家登出/服务器停止时）。 */
    public static void clearAll() { STORE.clear(); }

    // ── 持久化 ──

    /** 保存全部容器到 PlayerDataAPI（Menu 关闭时调用）。 */
    public static void saveToPlayerData(Player player, Data data) {
        JsonObject root = new JsonObject();
        root.addProperty("skill_upgrade", serialize(data.skillUpgrade.getItem(0), player));
        root.addProperty("big_load", serialize(data.bigLoad.getItem(0), player));
        root.add("skill_load", serializeContainer(data.skillLoad, 3, player));
        root.add("passive_load", serializeContainer(data.passiveLoad, 3, player));
        root.add("library", serializeContainer(data.skillLibrary, 20, player));
        root.add("equipment", serializeContainer(data.equipment, 6, player));
        savePlayerData(player, PERSIST_KEY, root.toString());
    }

    /** 从 PlayerDataAPI 恢复容器内容（Menu 打开时调用）。 */
    public static void loadFromPlayerData(Player player, Data data) {
        try {
            String raw = loadPlayerData(player, PERSIST_KEY);
            if (raw == null || raw.isEmpty()) return;
            JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
            deserializeInto(root, "skill_upgrade", data.skillUpgrade, 0, player);
            deserializeInto(root, "big_load", data.bigLoad, 0, player);
            deserializeContainerInto(root, "skill_load", data.skillLoad, 3, player);
            deserializeContainerInto(root, "passive_load", data.passiveLoad, 3, player);
            deserializeContainerInto(root, "library", data.skillLibrary, 20, player);
            deserializeContainerInto(root, "equipment", data.equipment, 6, player);
        } catch (Exception ignored) {}
    }

    private static String serialize(ItemStack stack, Player player) {
        if (stack.isEmpty()) return "";
        // 1.20.1：无 registryAccess 参与的 save(CompoundTag)（原 1.21.1 用 stack.save(player.registryAccess())）。
        // 基础物品 NBT 足够技能容器往返；如需 data-pack 属性等 registry 数据后续再补。
        return stack.save(new CompoundTag()).toString();
    }

    private static JsonArray serializeContainer(SimpleContainer c, int size, Player player) {
        JsonArray arr = new JsonArray();
        for (int i = 0; i < size; i++)
            arr.add(serialize(c.getItem(i), player));
        return arr;
    }

    private static void deserializeInto(JsonObject root, String key, SimpleContainer c, int slot, Player player) {
        if (!root.has(key)) return;
        String snbt = root.get(key).getAsString();
        if (snbt.isEmpty()) return;
        try {
            // 原 1.21.1：ItemStack.parse(player.registryAccess(), TagParser.parseTag(snbt)).ifPresent(...)
            c.setItem(slot, ItemStack.of(TagParser.parseTag(snbt)));
        } catch (Exception ignored) {}
    }

    private static void deserializeContainerInto(JsonObject root, String key, SimpleContainer c, int size, Player player) {
        if (!root.has(key)) return;
        JsonArray arr = root.getAsJsonArray(key);
        for (int i = 0; i < Math.min(size, arr.size()); i++) {
            String snbt = arr.get(i).getAsString();
            if (snbt.isEmpty()) continue;
            try {
                final int idx = i;
                c.setItem(idx, ItemStack.of(TagParser.parseTag(snbt)));
            } catch (Exception ignored) {}
        }
    }

    // TODO(1.20.1-port): 依赖 api/PlayerDataAPI（1.21.1 持久化 API，未移植）。
    // 原实现：PlayerDataAPI.set(player, key, value) / PlayerDataAPI.get(player, key)。
    // 待 PlayerDataAPI 移植后替换下方两个方法体，其余代码零改动。
    private static void savePlayerData(Player player, String key, String value) {
        // 暂不持久化：内存容器仍可用，服务端重启后配置丢失。
    }
    private static String loadPlayerData(Player player, String key) {
        return null;
    }
}
