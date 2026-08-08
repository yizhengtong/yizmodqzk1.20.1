package net.minecraft.client.yiz.handler;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.yiz.attribute.YizAttributes;
import net.minecraft.client.yiz.editor.SkillConfigStorage;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * 技能充能式冷却系统。
 *
 * <p>每个装载槽（big/s0/s1/s2）独立维护 {@code charges}（当前可用次数）和 {@code rechargeEnd}
 *（下一个充能完成的 gameTick）。放一次技能消耗 1 充能；未满时每 {@code cooldown_value} 回复 1，
 * 满上限（{@code max_charges}）停止回充。</p>
 *
 * <h3>大装载槽（slot 0）特权</h3>
 * <ul>
 *   <li>最大充能数 ×2</li>
 *   <li>冷却值（回充间隔）×0.5</li>
 * </ul>
 *
 * <h3>与冻结机制的关系</h3>
 * <p>{@code PassiveChargeTracker} 的冻结写的是 {@code skill_cooldowns} 哨兵；充能数据存独立的
 * {@code yizmodqzk:skill_charges} 键。冻结期间本管理器跳过该槽的回充（{@link #isFrozenSlot}）。</p>
 *
 * <p>1.20.1 移植：
 * <ul>
 *   <li>DataComponent（ATTRIBUTE_MODIFIERS）→ {@code ItemStack#getAttributeModifiers(EquipmentSlot)}
 *       的 Multimap 遍历（见 {@link #readAttr}）。</li>
 *   <li>YizAttributes.MAX_CHARGES / COOLDOWN_VALUE 尚未注册到 1.20.1（TODO，见使用处）。</li>
 *   <li>PlayerDataAPI（未移植）→ {@link #savePlayerData}/{@link #loadPlayerData} TODO 占位。</li>
 *   <li>PassiveChargeTracker（未移植）→ {@link #maxChargesOf(ItemStack,int,Player)} 的临时 buff 分支暂跳过。</li>
 * </ul></p>
 */
public final class SkillChargeManager {

    private static final String CHARGES_KEY = "yizmodqzk:skill_charges";
    private static final String[] SLOTS = {"big", "s0", "s1", "s2"};
    /** slot 索引 → 槽 key（0=big 大槽，1-3=技能槽）。 */
    public static String slotKey(int slot) {
        return (slot >= 0 && slot < SLOTS.length) ? SLOTS[slot] : "s" + slot;
    }

    private SkillChargeManager() {}

    // ════════════════════════════════════════════════════════════
    //  大槽特权：有效上限 / 有效冷却
    // ════════════════════════════════════════════════════════════

    /** 该槽位物品的有效最大充能数（大槽 ×2，最少 1，不含临时 buff）。 */
    public static int maxChargesOf(ItemStack stack, int slot) {
        if (stack.isEmpty()) return 1;
        // TODO(1.20.1-port): 依赖 attribute/YizAttributes.MAX_CHARGES 注册（1.21.1 id "max_charges"）。
        int base = (int) Math.max(1, readAttr(stack, YizAttributes.MAX_CHARGES.get()));
        return slot == 0 ? base * 2 : base;
    }

    /**
     * 该槽位物品的有效最大充能数（含临时 buff：满6次攻击奖励给所有技能 +1 上限）。
     * 传 null player 则不含 buff。
     */
    public static int maxChargesOf(ItemStack stack, int slot, Player player) {
        int max = maxChargesOf(stack, slot);
        // TODO(1.20.1-port): 依赖 handler/PassiveChargeTracker（1.21.1，未移植）。
        // 原逻辑：if (player != null && PassiveChargeTracker.hasTempBuff(player)) return max + 1;
        return max;
    }

    // ── 临时 buff（满6次攻击奖励）──

    /** 满充能触发：所有装载槽 max +1 并把 charges 补满到新上限。 */
    public static void grantTempBuff(ServerPlayer player) {
        JsonObject root = readRoot(player);
        for (int slot = 0; slot < 4; slot++) {
            String key = SLOTS[slot];
            ItemStack item = getItemAtSlot(player, slot);
            if (item.isEmpty()) continue;
            int max = maxChargesOf(item, slot, player); // 含本次 +1
            JsonObject entry = entryOf(root, key);
            entry.addProperty("charges", max); // 补满
            entry.addProperty("rechargeEnd", 0L);
            root.add(key, entry);
        }
        savePlayerData(player, CHARGES_KEY, root.toString());
    }

    /** 使用技能后消耗 buff：所有槽 max 回到基础值，charges 超过基础的作废。 */
    public static void removeTempBuff(ServerPlayer player) {
        JsonObject root = readRoot(player);
        boolean dirty = false;
        for (int slot = 0; slot < 4; slot++) {
            String key = SLOTS[slot];
            ItemStack item = getItemAtSlot(player, slot);
            if (item.isEmpty()) continue;
            int base = maxChargesOf(item, slot); // 不含 buff
            JsonObject entry = entryOf(root, key);
            int charges = entry.has("charges") ? entry.get("charges").getAsInt() : base;
            if (charges > base) {
                entry.addProperty("charges", base);
                root.add(key, entry);
                dirty = true;
            }
        }
        if (dirty) savePlayerData(player, CHARGES_KEY, root.toString());
    }

    /** 该槽位物品的有效回充间隔 tick（大槽 ×0.5，最少 1）。 */
    public static int cooldownOf(ItemStack stack, int slot) {
        if (stack.isEmpty()) return 20;
        // TODO(1.20.1-port): 依赖 attribute/YizAttributes.COOLDOWN_VALUE 注册（1.21.1 id "cooldown_value"）。
        int base = (int) Math.max(1, readAttr(stack, YizAttributes.COOLDOWN_VALUE.get()));
        return slot == 0 ? Math.max(1, base / 2) : base;
    }

    // ════════════════════════════════════════════════════════════
    //  施法消耗
    // ════════════════════════════════════════════════════════════

    /**
     * 施法时尝试消耗 1 充能。充能 >0 则 -1 并写回（并在未满时启动回充计时），返回 true；
     * 充能为 0 返回 false（拒绝施法）。
     */
    public static boolean tryConsume(ServerPlayer player, int slot) {
        JsonObject root = readRoot(player);
        String key = slotKey(slot);
        long now = player.level().getGameTime();

        ItemStack item = getItemAtSlot(player, slot);
        int max = maxChargesOf(item, slot, player);

        // 懒初始化：该槽位有物品但从未写入充能数据 → 初始化为满充能
        if (!root.has(key) && !item.isEmpty()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("charges", max);
            entry.addProperty("rechargeEnd", 0L);
            root.add(key, entry);
            savePlayerData(player, CHARGES_KEY, root.toString());
        }

        int charges = getCharges(root, key);
        if (charges <= 0) return false;

        charges -= 1;
        JsonObject entry = entryOf(root, key);
        entry.addProperty("charges", charges);

        // 消耗后若未满 → 启动/保持回充计时
        if (charges < max) {
            int cd = cooldownOf(item, slot);
            entry.addProperty("rechargeEnd", now + cd);
        } else {
            entry.addProperty("rechargeEnd", 0L);
        }
        root.add(key, entry);
        savePlayerData(player, CHARGES_KEY, root.toString());
        return true;
    }

    // ════════════════════════════════════════════════════════════
    //  回充 tick（由 tizMod.onPlayerTick 调用，服务端）
    // ════════════════════════════════════════════════════════════

    /** 每 tick 推进各槽充能：到点 +1，未满续设计时。仅在状态变化时写回（避免每 tick 同步）。 */
    public static void tickRecharge(ServerPlayer player) {
        JsonObject root = readRoot(player);
        long now = player.level().getGameTime();
        boolean dirty = false;

        for (int slot = 0; slot < 4; slot++) {
            String key = SLOTS[slot];
            ItemStack item = getItemAtSlot(player, slot);
            if (item.isEmpty()) continue; // 空槽不处理
            int max = maxChargesOf(item, slot, player); // 含临时 buff
            JsonObject entry = entryOf(root, key);
            // 懒初始化：entry 存在但无 charges 字段（新槽位首次 tick）→ 满充能并持久化
            if (!entry.has("charges")) {
                entry.addProperty("charges", max);
                entry.addProperty("rechargeEnd", 0L);
                root.add(key, entry);
                dirty = true;
                continue;
            }
            int charges = entry.get("charges").getAsInt();

            if (charges >= max) {
                // 已满：确保无回充计时
                if (entry.has("rechargeEnd") && entry.get("rechargeEnd").getAsLong() != 0L) {
                    entry.addProperty("rechargeEnd", 0L);
                    root.add(key, entry);
                    dirty = true;
                }
                continue;
            }
            // 未满 → 推进回充
            long rechargeEnd = entry.has("rechargeEnd") ? entry.get("rechargeEnd").getAsLong() : 0L;
            if (rechargeEnd <= 0) {
                // 未在回充却未满（如刚登录）→ 启动
                int cd = cooldownOf(item, slot);
                entry.addProperty("rechargeEnd", now + cd);
                root.add(key, entry);
                dirty = true;
                continue;
            }
            if (now >= rechargeEnd) {
                // 到点 +1
                charges += 1;
                entry.addProperty("charges", charges);
                if (charges < max) {
                    int cd = cooldownOf(item, slot);
                    entry.addProperty("rechargeEnd", now + cd);
                } else {
                    entry.addProperty("rechargeEnd", 0L);
                }
                root.add(key, entry);
                dirty = true;
            }
        }
        if (dirty) savePlayerData(player, CHARGES_KEY, root.toString());
    }

    // ════════════════════════════════════════════════════════════
    //  登录初始化：空数据视为各槽满充能（不卡玩家）
    // ════════════════════════════════════════════════════════════

    public static void onLogin(ServerPlayer player) {
        JsonObject root = readRoot(player);
        boolean dirty = false;
        long now = player.level().getGameTime();
        for (int slot = 0; slot < 4; slot++) {
            String key = SLOTS[slot];
            ItemStack item = getItemAtSlot(player, slot);
            if (item.isEmpty()) continue;
            if (!root.has(key)) {
                int max = maxChargesOf(item, slot);
                JsonObject entry = new JsonObject();
                entry.addProperty("charges", max);
                entry.addProperty("rechargeEnd", 0L);
                root.add(key, entry);
                dirty = true;
            }
        }
        if (dirty) savePlayerData(player, CHARGES_KEY, root.toString());
    }

    // ════════════════════════════════════════════════════════════
    //  读取（供 HUD 用，客户端）
    // ════════════════════════════════════════════════════════════

    public static int getCharges(Player player, int slot) {
        JsonObject root = readRoot(player);
        return getCharges(root, slotKey(slot));
    }

    public static long getRechargeEnd(Player player, int slot) {
        JsonObject root = readRoot(player);
        String key = slotKey(slot);
        if (!root.has(key)) return 0L;
        JsonObject entry = root.getAsJsonObject(key);
        return entry.has("rechargeEnd") ? entry.get("rechargeEnd").getAsLong() : 0L;
    }

    // ════════════════════════════════════════════════════════════
    //  内部
    // ════════════════════════════════════════════════════════════

    private static JsonObject readRoot(Player player) {
        String raw;
        try { raw = loadPlayerData(player, CHARGES_KEY); } catch (Exception e) { raw = null; }
        if (raw == null || raw.isEmpty()) return new JsonObject();
        try { return JsonParser.parseString(raw).getAsJsonObject(); }
        catch (Exception e) { return new JsonObject(); }
    }

    private static int getCharges(JsonObject root, String key) {
        if (!root.has(key)) return 0;
        JsonObject entry = root.getAsJsonObject(key);
        return entry.has("charges") ? entry.get("charges").getAsInt() : 0;
    }

    private static JsonObject entryOf(JsonObject root, String key) {
        if (root.has(key) && root.get(key).isJsonObject()) return root.getAsJsonObject(key);
        JsonObject entry = new JsonObject();
        root.add(key, entry);
        return entry;
    }

    private static ItemStack getItemAtSlot(ServerPlayer sp, int slot) {
        var data = SkillConfigStorage.get(sp.getUUID());
        if (data == null) return ItemStack.EMPTY;
        return switch (slot) {
            case 0 -> data.bigLoad().getItem(0);
            case 1 -> data.skillLoad().getItem(0);
            case 2 -> data.skillLoad().getItem(1);
            case 3 -> data.skillLoad().getItem(2);
            default -> ItemStack.EMPTY;
        };
    }

    /** 累加物品全部装备槽位中给定属性的修饰符数值（1.20.1 无 DataComponent，改用 Multimap）。 */
    private static double readAttr(ItemStack stack, Attribute attr) {
        double val = 0;
        for (var slot : net.minecraft.world.entity.EquipmentSlot.values()) {
            for (var mod : stack.getAttributeModifiers(slot).get(attr)) {
                val += mod.getAmount();
            }
        }
        return val;
    }

    /** 减少指定槽位剩余冷却时间（百分比，0.2 = 减少 20%）。 */
    public static void reduceCooldown(ServerPlayer player, int slot, float fraction) {
        JsonObject root = readRoot(player);
        String key = slotKey(slot);
        if (!root.has(key)) return;
        long now = player.level().getGameTime();
        JsonObject entry = entryOf(root, key);
        if (!entry.has("rechargeEnd")) return;
        long end = entry.get("rechargeEnd").getAsLong();
        if (end <= now) return;
        long remaining = end - now;
        long reduction = (long) (remaining * fraction);
        entry.addProperty("rechargeEnd", end - reduction);
        root.add(key, entry);
        savePlayerData(player, CHARGES_KEY, root.toString());
    }

    // TODO(1.20.1-port): 依赖 api/PlayerDataAPI（1.21.1 持久化 API，未移植）。
    // 原实现：PlayerDataAPI.set(player, key, value) / PlayerDataAPI.get(player, key)。
    // 待 PlayerDataAPI 移植后替换下方两个方法体，其余代码零改动。
    private static void savePlayerData(Player player, String key, String value) {
        // 暂不持久化：内存 JsonObject 逻辑保留，服务端重启后充能数据丢失。
    }
    private static String loadPlayerData(Player player, String key) {
        return null;
    }
}
