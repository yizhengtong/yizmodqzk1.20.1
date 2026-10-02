package net.minecraft.client.yiz.core;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 玩家身份存储（{@code config/yizmodqzk/players.json}，{@code _version: 2}）—— <b>本模组唯一写入该文件的地方</b>。
 *
 * <p><b>为什么需要它</b>：离线模式（启动器生成的离线 UUID）里 UUID 由<b>名字</b>派生，改一次名就换一个 UUID，
 * 于是按 UUID 存的一切（物品功能配置、个体开关）全部变成孤儿 —— 这是数据丢失，不是配置问题。换皮肤不影响 UUID。</p>
 *
 * <p><b>身份判定的信任边界</b>：只认服务端<b>亲眼看到</b>的事实（谁用什么 UUID/名字登录），
 * 不接受任何客户端文件作为凭据。所以"把校验文件复制给别人"在本设计里没有意义：
 * 文件最多是「令牌」的携带方式，而令牌只用来发起一次<b>待批准</b>的认领。</p>
 *
 * <p><b>三条继承规则</b>：</p>
 * <ol>
 *   <li><b>旧 UUID 命中</b>（同一人换回旧号/别名）：直接改挂到新 UUID，物品配置与个体开关全部保留；</li>
 *   <li><b>名字命中</b>（改名）：<b>自动继承物品配置</b>（不改会白丢），但<b>个体开关不自动继承</b>——
 *       那是权限类，必须走服主 {@code /yiz player migrate} 或令牌认领批准；</li>
 *   <li><b>都对不上</b>：建新记录；改名+换号的人用「一次性令牌 + 服主批准」认领。</li>
 * </ol>
 */
public final class PlayerIdentityStore {

    private static final String FILE_NAME = "yizmodqzk/players.json";
    private static final int VERSION = 2;

    /** 个体开关：不受生物改血影响（危险开关的个体项）。 */
    public static final String FLAG_MOB_EXEMPT = "mob_exempt";

    // ==================== 数据模型 ====================

    /** 一个人 = 一条记录（可含多个 UUID 变体与名字历史）。 */
    private static final class Rec {
        final List<String> uuids = new ArrayList<>();          // [0] = 当前主 UUID
        final List<String> names = new ArrayList<>();          // 名字历史（旧→新）
        final Map<String, Boolean> flags = new LinkedHashMap<>();
        final Map<String, Map<String, Boolean>> itemcfg = new LinkedHashMap<>();
        long firstSeen;
        long lastSeen;
        String token;                                          // 一次性认领令牌
        String supersededBy;                                   // 已被哪条记录取代（墓碑）
    }

    /** 待批准的认领申请。 */
    private static final class Pending {
        final String uuid;
        final String name;
        final String targetKey;                                // 认领哪条记录（记录当前主 UUID 串）
        final long at;

        Pending(String uuid, String name, String targetKey, long at) {
            this.uuid = uuid;
            this.name = name;
            this.targetKey = targetKey;
            this.at = at;
        }
    }

    private static final Map<String, Rec> BY_UUID = new LinkedHashMap<>();
    private static final List<Pending> PENDING = new ArrayList<>();
    private static final List<JsonObject> MIGRATIONS = new ArrayList<>();
    private static volatile boolean loaded = false;
    private static final SecureRandom RANDOM = new SecureRandom();

    private PlayerIdentityStore() {}

    // ==================== 读 ====================

    /** 个体开关（按 UUID；查不到再按名字兜底，覆盖"加入事件之前就有人查询"的时序）。 */
    public static boolean flag(UUID id, String flag) {
        Rec r = find(id == null ? null : id.toString());
        return r != null && Boolean.TRUE.equals(r.flags.get(flag));
    }

    /** 个体开关（带玩家对象：UUID 与名字都能对上）。 */
    public static boolean flag(Player p, String flag) {
        if (p == null) return false;
        Rec r = BY_UUID.get(p.getUUID().toString());
        if (r == null) r = findByName(name(p));
        return r != null && Boolean.TRUE.equals(r.flags.get(flag));
    }

    public static void setFlag(UUID id, String flag, boolean value) {
        Rec r = ensure(id == null ? null : id.toString(), null);
        if (r == null) return;
        if (value) r.flags.put(flag, true);
        else r.flags.remove(flag);
        save();
    }

    /** 物品功能配置：{@code itemId → feature → disabled}（null 表示没有玩家级覆盖）。 */
    public static Boolean itemFeature(UUID id, ResourceLocation item, String feature) {
        Rec r = find(id == null ? null : id.toString());
        if (r == null) return null;
        Map<String, Boolean> f = r.itemcfg.get(item.toString());
        return f == null ? null : f.get(feature);
    }

    public static void setItemFeature(UUID id, ResourceLocation item, String feature, Boolean disabled) {
        Rec r = ensure(id == null ? null : id.toString(), null);
        if (r == null || item == null || feature == null) return;
        Map<String, Boolean> f = r.itemcfg.computeIfAbsent(item.toString(), k -> new LinkedHashMap<>());
        if (disabled == null) f.remove(feature);
        else f.put(feature, disabled);
        if (f.isEmpty()) r.itemcfg.remove(item.toString());
        save();
    }

    // ==================== 加入事件：别名/名字识别 ====================

    /**
     * 玩家加入时调用：登记 UUID/名字，并按三条规则处理身份漂移。
     *
     * @return 人类可读的处理结果（用于日志），无变化返回 null
     */
    public static String onJoin(Player p) {
        if (p == null) return null;
        load();
        String id = p.getUUID().toString();
        String nm = name(p);
        long now = System.currentTimeMillis() / 1000L;

        Rec r = BY_UUID.get(id);
        if (r != null) {
            r.lastSeen = now;
            if (nm != null && !r.names.contains(nm)) r.names.add(nm);
            save();
            return null;
        }
        // ① 旧 UUID 命中（别名）→ 整条改挂到新 UUID（物品配置与个体开关都保留）
        Rec byAlias = null;
        for (Rec c : BY_UUID.values()) {
            if (c.uuids.size() > 1 && c.uuids.subList(1, c.uuids.size()).contains(id)) {
                byAlias = c;
                break;
            }
        }
        if (byAlias != null) {
            rekey(byAlias, id, nm, now, "alias", null);
            return "识别为旧 UUID 别名，已整条迁移（含个体开关）";
        }
        // ② 名字命中 → 自动继承「物品配置」，个体开关不继承（权限类必须人工批准）
        Rec byName = findByName(nm);
        if (byName != null && byName.supersededBy == null) {
            Rec fresh = new Rec();
            fresh.uuids.add(id);
            if (nm != null) fresh.names.add(nm);
            fresh.firstSeen = now;
            fresh.lastSeen = now;
            for (String n : byName.names) if (!fresh.names.contains(n)) fresh.names.add(n);
            for (Map.Entry<String, Map<String, Boolean>> e : byName.itemcfg.entrySet()) {
                fresh.itemcfg.put(e.getKey(), new LinkedHashMap<>(e.getValue()));
            }
            BY_UUID.put(id, fresh);
            byName.supersededBy = id;
            audit(byName.uuids.get(0), id, "auto:name", null);
            save();
            return "按名字识别到改名：已继承物品配置（" + fresh.itemcfg.size()
                + " 项）；个体开关未继承，需服主 /yiz player migrate " + shortId(id) + " " + shortId(byName.uuids.get(0));
        }
        // ③ 全新玩家
        Rec created = new Rec();
        created.uuids.add(id);
        if (nm != null) created.names.add(nm);
        created.firstSeen = now;
        created.lastSeen = now;
        BY_UUID.put(id, created);
        save();
        return null;
    }

    // ==================== 令牌认领（服主批准） ====================

    /** 给某条记录签发/轮换一次性认领令牌。 */
    public static String issueToken(String key) {
        Rec r = find(key);
        if (r == null) return null;
        byte[] buf = new byte[9];
        RANDOM.nextBytes(buf);
        StringBuilder sb = new StringBuilder();
        for (byte b : buf) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        r.token = sb.toString();
        save();
        return r.token;
    }

    /**
     * 玩家用令牌发起认领 → 只生成一条<b>待批准</b>记录（不直接授权）。
     *
     * @return 结果文本
     */
    public static String claim(String token, Player p) {
        if (token == null || p == null) return "参数不完整";
        load();
        Rec target = null;
        for (Rec r : BY_UUID.values()) {
            if (token.equalsIgnoreCase(r.token)) {
                target = r;
                break;
            }
        }
        if (target == null) return "令牌无效";
        String id = p.getUUID().toString();
        if (id.equals(target.uuids.get(0))) return "你就是这条记录本人，无需认领";
        for (Pending q : PENDING) if (q.uuid.equals(id)) return "你已经有一条待批准的认领，等服主处理";
        PENDING.add(new Pending(id, name(p), target.uuids.get(0), System.currentTimeMillis() / 1000L));
        save();
        return "已提交认领申请，等待服主 /yiz player approve 批准";
    }

    /** 待批准列表（含文字化的目标）。 */
    public static List<String> pendingLines() {
        load();
        List<String> out = new ArrayList<>();
        for (int i = 0; i < PENDING.size(); i++) {
            Pending q = PENDING.get(i);
            out.add("#" + i + " " + q.name + " (" + shortId(q.uuid) + ") → 认领 " + shortId(q.targetKey)
                + " 的名字 " + String.join("/", namesOf(q.targetKey)));
        }
        return out;
    }

    /** 批准第 idx 条认领：把目标记录的物品配置与个体开关并入该玩家。 */
    public static String approve(int idx, String by) {
        load();
        if (idx < 0 || idx >= PENDING.size()) return "序号不存在";
        Pending q = PENDING.remove(idx);
        Rec target = BY_UUID.get(q.targetKey);
        if (target == null) {
            save();
            return "目标记录已不存在（可能已被合并）";
        }
        merge(target, q.uuid, q.name, "token:" + q.uuid, by);
        save();
        return "已批准：" + q.name + " 继承 " + shortId(q.targetKey) + " 的物品配置与个体开关（审计已记录）";
    }

    public static String reject(int idx) {
        load();
        if (idx < 0 || idx >= PENDING.size()) return "序号不存在";
        Pending q = PENDING.remove(idx);
        save();
        return "已拒绝：" + q.name + " 的认领申请";
    }

    /** 服主手工合并：目标 ← 来源（物品配置 + 个体开关 + 名字历史）。键可以是 UUID 或玩家名。 */
    public static String migrate(String targetKey, String sourceKey, String by) {
        load();
        Rec src = find(sourceKey);
        if (src == null) return "找不到来源记录：" + sourceKey;
        String targetId = resolveId(targetKey);
        if (targetId == null) return "找不到目标：" + targetKey;
        if (targetId.equals(src.uuids.get(0))) return "来源与目标是同一条记录";
        merge(src, targetId, null, "manual", by);
        save();
        return "已合并：" + shortId(src.uuids.get(0)) + " → " + shortId(targetId) + "（物品配置与个体开关已并入）";
    }

    // ==================== 展示 ====================

    /** 每条记录一行摘要（供指令列出）。 */
    public static List<String> listLines() {
        load();
        List<String> out = new ArrayList<>();
        for (Rec r : BY_UUID.values()) {
            StringBuilder sb = new StringBuilder();
            sb.append(shortId(r.uuids.get(0)));
            if (r.uuids.size() > 1) sb.append(" +").append(r.uuids.size() - 1).append("旧号");
            sb.append(" 名字=").append(r.names.isEmpty() ? "?" : String.join("/", r.names));
            sb.append(" 物品配置=").append(r.itemcfg.size());
            if (!r.flags.isEmpty()) sb.append(" 个体开关=").append(r.flags.keySet());
            if (r.token != null) sb.append(" 有令牌");
            if (r.supersededBy != null) sb.append(" §8(已被 ").append(shortId(r.supersededBy)).append(" 取代)");
            out.add(sb.toString());
        }
        return out;
    }

    /** 按 UUID 或名字找记录的当前主键（供指令解析）。 */
    public static String resolveId(String key) {
        if (key == null) return null;
        Rec r = find(key);
        return r == null ? null : r.uuids.get(0);
    }

    public static List<String> namesOf(String key) {
        Rec r = find(key);
        return r == null ? List.of() : List.copyOf(r.names);
    }

    // ==================== 持久化 ====================

    public static void load() {
        if (loaded) return;
        synchronized (PlayerIdentityStore.class) {
            if (loaded) return;
            try {
                Path p = FMLPaths.CONFIGDIR.get().resolve(FILE_NAME);
                if (!Files.exists(p)) {
                    loaded = true;
                    return;
                }
                JsonObject root = JsonParser.parseString(Files.readString(p)).getAsJsonObject();
                int ver = root.has("_version") ? root.get("_version").getAsInt() : 1;
                JsonObject players = root.getAsJsonObject("players");
                if (players != null) {
                    for (String key : players.keySet()) {
                        JsonObject o = players.getAsJsonObject(key);
                        Rec r = new Rec();
                        r.uuids.add(key);
                        if (ver >= 2) readV2(r, o);
                        else readV1AsItemCfg(r, o);          // v1：players.<uuid>.<item>.<feature>
                        BY_UUID.put(key, r);
                    }
                }
                if (ver >= 2) {
                    JsonArray pend = root.getAsJsonArray("pending");
                    if (pend != null) {
                        for (JsonElement e : pend) {
                            JsonObject o = e.getAsJsonObject();
                            PENDING.add(new Pending(str(o, "uuid"), str(o, "name"), str(o, "target"),
                                num(o, "at")));
                        }
                    }
                    JsonArray mig = root.getAsJsonArray("migrations");
                    if (mig != null) for (JsonElement e : mig) MIGRATIONS.add(e.getAsJsonObject());
                }
                if (ver < VERSION) save();                    // 立刻升级落盘（旧文件已另有 .bak）
            } catch (Throwable t) {
                // 解析失败：保留原文件不动，本次按空表运行（下一次写入前会做 .bak）
            }
            loaded = true;
        }
    }

    private static void readV2(Rec r, JsonObject o) {
        JsonArray us = o.getAsJsonArray("uuids");
        if (us != null) for (JsonElement e : us) {
            String s = e.getAsString();
            if (!r.uuids.contains(s)) r.uuids.add(s);
        }
        JsonArray ns = o.getAsJsonArray("names");
        if (ns != null) for (JsonElement e : ns) r.names.add(e.getAsString());
        r.firstSeen = num(o, "firstSeen");
        r.lastSeen = num(o, "lastSeen");
        r.token = o.has("token") && !o.get("token").isJsonNull() ? o.get("token").getAsString() : null;
        r.supersededBy = o.has("supersededBy") && !o.get("supersededBy").isJsonNull()
            ? o.get("supersededBy").getAsString() : null;
        JsonObject fl = o.getAsJsonObject("flags");
        if (fl != null) for (String k : fl.keySet()) if (fl.get(k).getAsBoolean()) r.flags.put(k, true);
        JsonObject ic = o.getAsJsonObject("itemcfg");
        if (ic != null) {
            for (String item : ic.keySet()) {
                Map<String, Boolean> feats = new LinkedHashMap<>();
                JsonObject fo = ic.getAsJsonObject(item);
                for (String f : fo.keySet()) feats.put(f, fo.get(f).getAsBoolean());
                r.itemcfg.put(item, feats);
            }
        }
    }

    /** v1 兼容：整个对象就是物品配置。 */
    private static void readV1AsItemCfg(Rec r, JsonObject o) {
        for (String item : o.keySet()) {
            JsonElement v = o.get(item);
            if (!v.isJsonObject()) continue;
            Map<String, Boolean> feats = new LinkedHashMap<>();
            JsonObject fo = v.getAsJsonObject();
            for (String f : fo.keySet()) feats.put(f, fo.get(f).getAsBoolean());
            r.itemcfg.put(item, feats);
        }
    }

    public static void save() {
        try {
            Path p = FMLPaths.CONFIGDIR.get().resolve(FILE_NAME);
            Files.createDirectories(p.getParent());
            if (Files.exists(p)) {
                Path bak = p.resolveSibling(FILE_NAME.substring(FILE_NAME.indexOf('/') + 1) + ".bak");
                Files.copy(p, bak, StandardCopyOption.REPLACE_EXISTING);
            }
            JsonObject root = new JsonObject();
            root.addProperty("_version", VERSION);
            JsonObject players = new JsonObject();
            for (Map.Entry<String, Rec> e : BY_UUID.entrySet()) {
                Rec r = e.getValue();
                JsonObject o = new JsonObject();
                JsonArray us = new JsonArray();
                r.uuids.forEach(us::add);
                o.add("uuids", us);
                JsonArray ns = new JsonArray();
                r.names.forEach(ns::add);
                o.add("names", ns);
                o.addProperty("firstSeen", r.firstSeen);
                o.addProperty("lastSeen", r.lastSeen);
                if (r.token != null) o.addProperty("token", r.token);
                if (r.supersededBy != null) o.addProperty("supersededBy", r.supersededBy);
                if (!r.flags.isEmpty()) {
                    JsonObject fl = new JsonObject();
                    r.flags.forEach((k, v) -> fl.addProperty(k, v));
                    o.add("flags", fl);
                }
                JsonObject ic = new JsonObject();
                r.itemcfg.forEach((item, feats) -> {
                    JsonObject fo = new JsonObject();
                    feats.forEach(fo::addProperty);
                    ic.add(item, fo);
                });
                o.add("itemcfg", ic);
                players.add(e.getKey(), o);
            }
            root.add("players", players);
            JsonArray pend = new JsonArray();
            for (Pending q : PENDING) {
                JsonObject o = new JsonObject();
                o.addProperty("uuid", q.uuid);
                o.addProperty("name", q.name);
                o.addProperty("target", q.targetKey);
                o.addProperty("at", q.at);
                pend.add(o);
            }
            root.add("pending", pend);
            JsonArray mig = new JsonArray();
            MIGRATIONS.forEach(mig::add);
            root.add("migrations", mig);
            Files.writeString(p, new GsonBuilder().setPrettyPrinting().create().toJson(root));
        } catch (Throwable ignored) {}
    }

    // ==================== 内部 ====================

    private static Rec find(String key) {
        if (key == null) return null;
        load();
        Rec r = BY_UUID.get(key);
        if (r != null) return r;
        for (Rec c : BY_UUID.values()) if (c.uuids.contains(key)) return c;
        return findByName(key);
    }

    private static Rec findByName(String nm) {
        if (nm == null) return null;
        Rec best = null;
        for (Rec c : BY_UUID.values()) {
            if (!c.names.contains(nm)) continue;
            if (c.supersededBy != null && best == null) {
                best = c;                                   // 墓碑也认，但没有活记录时优先活记录
                continue;
            }
            if (c.supersededBy == null) return c;
        }
        return best;
    }

    private static Rec ensure(String id, String nm) {
        if (id == null) return null;
        load();
        Rec r = BY_UUID.get(id);
        if (r != null) return r;
        Rec created = new Rec();
        created.uuids.add(id);
        if (nm != null) created.names.add(nm);
        created.firstSeen = System.currentTimeMillis() / 1000L;
        created.lastSeen = created.firstSeen;
        BY_UUID.put(id, created);
        return created;
    }

    /** 把整条记录改挂到新 UUID（保留物品配置与个体开关）。 */
    private static void rekey(Rec r, String newId, String nm, long now, String how, String by) {
        String oldId = r.uuids.get(0);
        BY_UUID.remove(oldId);
        r.uuids.remove(newId);
        r.uuids.add(0, newId);
        if (!r.uuids.contains(oldId)) r.uuids.add(oldId);
        if (nm != null && !r.names.contains(nm)) r.names.add(nm);
        r.lastSeen = now;
        BY_UUID.put(newId, r);
        audit(oldId, newId, how, by);
    }

    /** 合并：来源记录的物品配置与个体开关并入目标记录。 */
    private static void merge(Rec src, String targetId, String targetName, String how, String by) {
        Rec dst = ensure(targetId, targetName);
        String srcId = src.uuids.get(0);
        if (dst == null || srcId.equals(dst.uuids.get(0))) return;
        for (String u : src.uuids) if (!dst.uuids.contains(u)) dst.uuids.add(u);
        for (String n : src.names) if (!dst.names.contains(n)) dst.names.add(n);
        dst.flags.putAll(src.flags);
        for (Map.Entry<String, Map<String, Boolean>> e : src.itemcfg.entrySet()) {
            dst.itemcfg.computeIfAbsent(e.getKey(), k -> new LinkedHashMap<>()).putAll(e.getValue());
        }
        dst.firstSeen = Math.min(dst.firstSeen == 0 ? src.firstSeen : dst.firstSeen, src.firstSeen);
        dst.lastSeen = Math.max(dst.lastSeen, src.lastSeen);
        src.supersededBy = dst.uuids.get(0);
        audit(srcId, dst.uuids.get(0), how, by);
    }

    private static void audit(String from, String to, String how, String by) {
        JsonObject o = new JsonObject();
        o.addProperty("from", from);
        o.addProperty("to", to);
        o.addProperty("how", how);
        if (by != null) o.addProperty("by", by);
        o.addProperty("at", System.currentTimeMillis() / 1000L);
        MIGRATIONS.add(o);
        while (MIGRATIONS.size() > 200) MIGRATIONS.remove(0);
    }

    private static String name(Player p) {
        try {
            return p.getGameProfile().getName();
        } catch (Throwable t) {
            try {
                return p.getName().getString();
            } catch (Throwable t2) {
                return null;
            }
        }
    }

    private static String shortId(String id) {
        if (id == null) return "?";
        return id.length() <= 8 ? id : id.substring(0, 8);
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : null;
    }

    private static long num(JsonObject o, String k) {
        try {
            return o.has(k) ? o.get(k).getAsLong() : 0L;
        } catch (Throwable t) {
            return 0L;
        }
    }
}
