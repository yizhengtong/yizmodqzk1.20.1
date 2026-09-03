package net.minecraft.client.yiz.itemcfg;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 适配层：声明式语义功能规则。
 *
 * <p>语义功能（仇恨免疫/取消受击/增伤）反射识别不了，由开发者写
 * {@code config/yizmodqzk/adapters/<modid>.json} 声明：</p>
 * <pre>
 * {
 *   "rules": [{
 *     "match": "cyclic:wand",
 *     "features": [
 *       {"feature": "AGGRO_IMMUNITY", "zh": "仇恨免疫", "gate": "MOB_TARGET"}
 *     ]
 *   }]
 * }
 * </pre>
 *
 * <p>match 支持 item id（"mod:item"）或 namespace 通配（"mod:*"）。</p>
 */
public final class AdapterRegistry {

    /** 语义功能声明。gate：门控类型（MOB_TARGET/HURT/DAMAGE_BOOST），extra：额外参数。 */
    public record AdapterFeature(String feature, String zh, String gate, Map<String, String> extra) {}

    public record AdapterRule(String match, List<AdapterFeature> features) {}

    private static final String ADAPTER_DIR = "yizmodqzk/adapters";
    private static final List<AdapterRule> RULES = new CopyOnWriteArrayList<>();

    private AdapterRegistry() {}

    public static void loadAll() {
        RULES.clear();
        try {
            Path dir = FMLPaths.CONFIGDIR.get().resolve(ADAPTER_DIR);
            if (!Files.isDirectory(dir)) return;
            try (var stream = Files.list(dir)) {
                stream.filter(p -> p.getFileName().toString().endsWith(".json"))
                        .sorted()
                        .forEach(AdapterRegistry::loadFile);
            }
        } catch (Exception ignored) {}
    }

    private static void loadFile(Path p) {
        try {
            JsonObject root = JsonParser.parseString(Files.readString(p)).getAsJsonObject();
            if (!root.has("rules")) return;
            for (var r : root.getAsJsonArray("rules")) {
                JsonObject ro = r.getAsJsonObject();
                String match = ro.get("match").getAsString();
                List<AdapterFeature> feats = new ArrayList<>();
                if (ro.has("features")) {
                    for (var f : ro.getAsJsonArray("features")) {
                        JsonObject fo = f.getAsJsonObject();
                        String feature = fo.get("feature").getAsString();
                        String zh = fo.has("zh") ? fo.get("zh").getAsString() : feature;
                        String gate = fo.has("gate") ? fo.get("gate").getAsString() : "";
                        Map<String, String> extra = new HashMap<>();
                        if (fo.has("extra")) {
                            fo.getAsJsonObject("extra").entrySet().forEach(e ->
                                    extra.put(e.getKey(), e.getValue().isJsonPrimitive()
                                            ? e.getValue().getAsString() : e.getValue().toString()));
                        }
                        feats.add(new AdapterFeature(feature, zh, gate, extra));
                    }
                }
                if (!feats.isEmpty()) RULES.add(new AdapterRule(match, feats));
            }
        } catch (Exception ignored) {}
    }

    /** 返回物品的语义功能声明（内置 vanilla 持有类功能 + match=item id 或 namespace:* 外部规则）。 */
    public static List<AdapterFeature> find(ResourceLocation itemId) {
        List<AdapterFeature> out = new ArrayList<>();
        // 内置 vanilla 持有类功能（反射识别不了的引擎级效果，如不死图腾免死）
        out.addAll(BuiltinItemConfigs.featuresFor(itemId));
        for (AdapterRule r : RULES) {
            if (matches(r.match(), itemId)) out.addAll(r.features());
        }
        return out;
    }

    public static boolean isKnown(ResourceLocation itemId, String feature) {
        for (AdapterFeature f : find(itemId)) {
            if (f.feature().equalsIgnoreCase(feature)) return true;
        }
        return false;
    }

    private static boolean matches(String match, ResourceLocation id) {
        String s = id.toString();
        if (match.equals(s)) return true;
        if (match.endsWith(":*")) return id.getNamespace().equals(match.substring(0, match.length() - 2));
        return false;
    }
}
