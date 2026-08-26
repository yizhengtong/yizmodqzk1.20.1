package net.minecraft.client.yiz.api;

import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.event.RegisterShadersEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 着色器注册保护注册表 — 受 Iris 保护的着色器注册中心
 *
 * <p>下游模组通过此注册表提交需要 Iris 光影兼容保护的着色器程序。
 * 前置库在 {@link RegisterShadersEvent} 触发时自动完成以下工作：</p>
 * <ol>
 *   <li>调用 {@link ShaderEnvironmentAPI#ensureShaderCompatibility()} 确保 Iris 兼容</li>
 *   <li>批量注册所有已登记的着色器到渲染管线</li>
 *   <li>将注册后的 {@link ShaderInstance} 引用存入缓存供下游取用</li>
 * </ol>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 在 @Mod 构造器或客户端入口中注册着色器
 * ShaderProtectionRegistry.registerShader(
 *     ResourceLocation.fromNamespaceAndPath("yizxgmod", "star_body"),
 *     DefaultVertexFormat.NEW_ENTITY
 * );
 *
 * // 在需要的地方获取 ShaderInstance 创建 RenderType
 * ShaderInstance shader = ShaderProtectionRegistry.getShader(yizxgMod.id("star_body"));
 * }</pre>
 */
// 大白话: 着色器保护方法
public final class ShaderProtectionRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(ShaderProtectionRegistry.class);

    private ShaderProtectionRegistry() {}

    // ─────────────────────────────────────────────────────────────────
    //  注册表存储
    // ─────────────────────────────────────────────────────────────────

    /** 已注册的着色器条目 */
    private static final List<ShaderEntry> ENTRIES = new CopyOnWriteArrayList<>();

    /** 注册完成后的 ShaderInstance 引用缓存 */
    private static final Map<ResourceLocation, ShaderInstance> SHADER_CACHE = new ConcurrentHashMap<>();

    /** 是否已完成首次注册（确保只注册一次） */
    private static volatile boolean registered = false;

    // ─────────────────────────────────────────────────────────────────
    //  公开 API — 下游模组调用
    // ─────────────────────────────────────────────────────────────────

    /**
     * 注册一个需要 Iris 兼容保护的着色器程序。
     *
     * <p>着色器的源码文件（.json + .glsl）需放置在 {@code assets/&lt;modid&gt;/shaders/} 目录下。
     * 实际注册发生在 {@link RegisterShadersEvent} 触发时，由前置库自动完成。</p>
     *
     * <p>此方法可在任意阶段调用（包括 {@code @Mod} 构造器和客户端入口），
     * 建议在客户端专用入口中调用。</p>
     *
     * @param id           着色器 ID（含命名空间）
     * @param vertexFormat 顶点格式（通常为 {@link com.mojang.blaze3d.vertex.DefaultVertexFormat#NEW_ENTITY}）
     * @throws IllegalArgumentException id 为 null
     */
    public static void registerShader(ResourceLocation id, VertexFormat vertexFormat) {
        if (id == null) throw new IllegalArgumentException("Shader id must not be null");
        if (vertexFormat == null) throw new IllegalArgumentException("VertexFormat must not be null");

        // 避免重复注册
        for (ShaderEntry entry : ENTRIES) {
            if (entry.id.equals(id)) {
                LOGGER.warn("Shader already registered: {}", id);
                return;
            }
        }

        ENTRIES.add(new ShaderEntry(id, vertexFormat));
        LOGGER.debug("Shader registered for protection: {} (format={})", id, vertexFormat);
    }

    /**
     * 获取已注册并加载完成的 {@link ShaderInstance}。
     *
     * <p>必须在 {@link RegisterShadersEvent} 触发后调用，否则返回 {@code null}。</p>
     *
     * @param id 着色器 ID
     * @return ShaderInstance，尚未注册或未加载时返回 null
     */
    public static ShaderInstance getShader(ResourceLocation id) {
        return SHADER_CACHE.get(id);
    }

    /**
     * 获取所有已注册的着色器条目（只读快照）。
     *
     * @return 已注册的着色器条目列表
     */
    public static List<ShaderEntry> getAllEntries() {
        return List.copyOf(ENTRIES);
    }

    /**
     * 检查指定着色器是否已成功加载。
     *
     * @param id 着色器 ID
     * @return true = 已加载可用
     */
    public static boolean isShaderLoaded(ResourceLocation id) {
        return SHADER_CACHE.containsKey(id) && SHADER_CACHE.get(id) != null;
    }

    // ─────────────────────────────────────────────────────────────────
    //  内部 — RegisterShadersEvent 处理（由 tizModClient 订阅）
    // ─────────────────────────────────────────────────────────────────

    /**
     * 处理 {@link RegisterShadersEvent}— 自动注册所有受保护的着色器。
     *
     * <p>在前置库客户端初始化时注册为 Mod 总线事件监听器。
     * 当事件触发时：</p>
     * <ol>
     *   <li>确保 Iris 兼容性（启用 allowUnknownShaders）</li>
     *   <li>遍历已注册条目，逐一调用 {@code event.registerShader()}</li>
     *   <li>将加载后的 {@link ShaderInstance} 存入缓存</li>
     * </ol>
     *
     * @param event RegisterShadersEvent
     */
    public static void onRegisterShaders(RegisterShadersEvent event) {
        if (ENTRIES.isEmpty()) return;

        // 第一步：确保 Iris 兼容性（允许未知着色器）
        boolean compatible = ShaderEnvironmentAPI.ensureShaderCompatibility();
        if (!compatible) {
            LOGGER.warn("Failed to ensure Iris shader compatibility — custom shaders may not render");
        } else if (ShaderEnvironmentAPI.isIrisLoaded()) {
            LOGGER.info("Iris shader compatibility ensured (allowUnknownShaders={})",
                    ShaderEnvironmentAPI.isUnknownShaderAllowed());
        }

        // 第二步：批量注册着色器
        for (ShaderEntry entry : ENTRIES) {
            ResourceLocation id = entry.id;
            try {
                ShaderInstance shader = new ShaderInstance(
                        event.getResourceProvider(),
                        id,
                        entry.vertexFormat
                );
                event.registerShader(shader, instance -> {
                    SHADER_CACHE.put(id, instance);
                    LOGGER.debug("Shader loaded and cached: {}", id);
                });
            } catch (IOException e) {
                LOGGER.error("Failed to load shader '{}': {}", id, e.getMessage());
            }
        }

        registered = true;
        LOGGER.info("Registered {} shader(s) with Iris protection", ENTRIES.size());
    }

    // ─────────────────────────────────────────────────────────────────
    //  内部数据类型
    // ─────────────────────────────────────────────────────────────────

    /**
     * 着色器注册条目。
     *
     * @param id           着色器资源 ID
     * @param vertexFormat 顶点格式
     */
    public record ShaderEntry(ResourceLocation id, VertexFormat vertexFormat) {
        public ShaderEntry {
            if (id == null) throw new IllegalArgumentException("id must not be null");
            if (vertexFormat == null) throw new IllegalArgumentException("vertexFormat must not be null");
        }
    }
}
