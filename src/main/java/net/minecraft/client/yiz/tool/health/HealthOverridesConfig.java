package net.minecraft.client.yiz.tool.health;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.fml.loading.FMLPaths;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 声明式改血覆盖配置（P0.5）：{@code config/yizmodqzk/health_overrides.json}。
 *
 * <p>对「行为探测无法自动确认」的槽/门控，允许以配置声明（不改代码、不硬编码进 jar）：</p>
 * <pre>
 * {
 *   "slots": {
 *     "模组实体类名": {"kind": "naccessor|string|accessor|field|codec", "field": "字段/SRG名",
 *                   "inverse": false, "meta": "b=1000;inverse=true"}
 *   },
 *   "gates": {
 *     "模组实体类名": [{"kind": "nbt|data|field", "name": "键/字段名", "value": false}]
 *   }
 * }
 * </pre>
 * <ul>
 *   <li>{@code slots} 声明式注入到 {@link EntityHealthLocator} 槽缓存（覆盖自动检测）；</li>
 *   <li>{@code gates} 在每次全量直改时强制写入（击穿门控，如路西法 EReady=false）。</li>
 * </ul>
 */
public final class HealthOverridesConfig {

    private static final String FILE_NAME = "yizmodqzk/health_overrides.json";
    private static final java.util.Map<String, JsonArray> GATE_DECLS = new ConcurrentHashMap<>();
    private static volatile boolean loaded = false;

    private HealthOverridesConfig() {}

    /** 声明式槽：实体类名 → 槽描述（kind/field/inverse/meta）。 */
    public record DeclaredSlot(String kind, String field, boolean inverse, String meta) {}

    public static void load() {
        if (loaded) return;
        loaded = true;
        try {
            Path p = FMLPaths.CONFIGDIR.get().resolve(FILE_NAME);
            if (!Files.exists(p)) return;
            JsonObject root = JsonParser.parseString(Files.readString(p)).getAsJsonObject();
            // slots → EntityHealthLocator 声明式缓存
            JsonObject slots = root.has("slots") ? root.getAsJsonObject("slots") : null;
            if (slots != null) {
                for (String cls : slots.keySet()) {
                    JsonObject o = slots.getAsJsonObject(cls);
                    String kind = o.has("kind") ? o.get("kind").getAsString() : "field";
                    String field = o.has("field") ? o.get("field").getAsString() : "";
                    boolean inverse = o.has("inverse") && o.get("inverse").getAsBoolean();
                    String meta = o.has("meta") ? o.get("meta").getAsString() : "";
                    EntityHealthLocator.declareSlot(cls, field, kind, inverse, meta);
                }
            }
            // gates → 全量直改时强制写入
            JsonObject gates = root.has("gates") ? root.getAsJsonObject("gates") : null;
            if (gates != null) {
                for (String cls : gates.keySet()) {
                    GATE_DECLS.put(cls, gates.getAsJsonArray(cls));
                }
            }
        } catch (Throwable t) {
            // 配置损坏不崩，仅记录
            try {
                net.minecraft.client.yiz.tizMod.LOGGER.warn("[Overrides] health_overrides.json 解析失败: {}", t.getMessage());
            } catch (Throwable ignored) {}
        }
    }

    /** 该类是否有声明式门控。 */
    public static boolean hasGates(String className) {
        return GATE_DECLS.containsKey(className);
    }

    /** 读取该类声明式门控（类名精确匹配；无则向上查找继承链）。 */
    public static List<GateDecl> gatesFor(LivingEntity entity) {
        List<GateDecl> out = new ArrayList<>();
        if (entity == null) return out;
        for (Class<?> c = entity.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            JsonArray arr = GATE_DECLS.get(c.getName());
            if (arr == null) continue;
            for (JsonElement el : arr) {
                try {
                    JsonObject o = el.getAsJsonObject();
                    out.add(new GateDecl(
                        o.has("kind") ? o.get("kind").getAsString() : "nbt",
                        o.has("name") ? o.get("name").getAsString() : "",
                        !o.has("value") || o.get("value").getAsBoolean()));
                } catch (Throwable ignored) {}
            }
        }
        return out;
    }

    /** 声明式门控：kind = nbt|data|field。 */
    public record GateDecl(String kind, String name, boolean value) {}

    /** 应用一条声明式门控。 */
    public static boolean applyGate(LivingEntity entity, GateDecl decl) {
        if (entity == null || decl == null || decl.name().isEmpty()) return false;
        try {
            switch (decl.kind()) {
                case "nbt":
                    entity.getPersistentData().putBoolean(decl.name(), decl.value());
                    return true;
                case "data": {
                    // 按字段名找静态 EntityDataAccessor<Boolean>
                    for (Class<?> c = entity.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                        try {
                            Field f = c.getDeclaredField(decl.name());
                            f.setAccessible(true);
                            Object acc = f.get(null);
                            if (acc instanceof net.minecraft.network.syncher.EntityDataAccessor<?> a
                                    && a.getSerializer() == net.minecraft.network.syncher.EntityDataSerializers.BOOLEAN) {
                                @SuppressWarnings("unchecked")
                                net.minecraft.network.syncher.EntityDataAccessor<Boolean> b =
                                    (net.minecraft.network.syncher.EntityDataAccessor<Boolean>) a;
                                return DirectHealthFallback.setBooleanChannelValue(entity, b, decl.value(), true);
                            }
                        } catch (NoSuchFieldException ignored) {}
                    }
                    return false;
                }
                case "field": {
                    for (Class<?> c = entity.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                        try {
                            Field f = c.getDeclaredField(decl.name());
                            f.setAccessible(true);
                            if (f.getType() == boolean.class) {
                                f.setBoolean(entity, decl.value());
                                return true;
                            }
                        } catch (NoSuchFieldException ignored) {}
                    }
                    return false;
                }
                default:
                    return false;
            }
        } catch (Throwable t) {
            return false;
        }
    }
}
