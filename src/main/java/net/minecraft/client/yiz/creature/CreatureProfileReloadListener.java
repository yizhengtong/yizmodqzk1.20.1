package net.minecraft.client.yiz.creature;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.JsonOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.EntityType;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;

import java.util.Map;

/**
 * 生物原型的数据包加载器。
 *
 * <p>目录 {@code data/<namespace>/yiz_creature/}，文件名相对路径即原型 id
 * （如 {@code yiz_creature/tiedoushi.json} → {@code yizxianmod:tiedoushi}）。</p>
 *
 * <p>每次重载先清空数据包轨（{@link CreatureProfileRegistry#clearData}），代码轨默认值不受影响；
 * 同 id 时数据包优先。若 JSON 声明了 {@code entity_type}，则同时把该实体类绑定到本原型。</p>
 */
public final class CreatureProfileReloadListener extends SimpleJsonResourceReloadListener {

    public static final String DIRECTORY = "yiz_creature";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Logger LOGGER = LogUtils.getLogger();

    public CreatureProfileReloadListener() {
        super(GSON, DIRECTORY);
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> objects, ResourceManager manager, ProfilerFiller profiler) {
        CreatureProfileRegistry.clearData();
        int loaded = 0;
        for (Map.Entry<ResourceLocation, JsonElement> entry : objects.entrySet()) {
            ResourceLocation id = entry.getKey();
            try {
                CreatureProfileJson json = CreatureProfileJson.CODEC
                    .parse(JsonOps.INSTANCE, entry.getValue())
                    .getOrThrow(false, msg -> LOGGER.warn("[Creature] 解析原型 {} 失败: {}", id, msg));
                CreatureProfileRegistry.registerData(json.toProfile(id));
                json.entityType().ifPresent(typeId -> bindEntityType(id, typeId));
                loaded++;
            } catch (Throwable t) {
                LOGGER.warn("[Creature] 加载原型 {} 异常: {}", id, t.toString());
            }
        }
        LOGGER.info("[Creature] 数据包原型加载完成：{} 条（目录 {}）", loaded, DIRECTORY);
        // 组件变更后推动存量实体：切到服务端主线程执行，避免在资源加载线程操作实体
        net.minecraft.server.MinecraftServer server =
            net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            server.execute(() -> {
                int n = CreatureComponentRefresher.refreshAll(server);
                if (n > 0) LOGGER.info("[Creature] 原型变更后已刷新存量实体 {} 个", n);
            });
        }
    }

    private static void bindEntityType(ResourceLocation profileId, ResourceLocation entityTypeId) {
        EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(entityTypeId);
        if (type == null) {
            LOGGER.warn("[Creature] 原型 {} 绑定的实体类型不存在：{}", profileId, entityTypeId);
            return;
        }
        CreatureProfileRegistry.bindClass(type.getBaseClass(), profileId);
    }
}
