package net.minecraft.client.yiz.api;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.client.event.RegisterShadersEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

import static net.minecraft.client.renderer.RenderStateShard.*;

/**
 * 中央着色器管理器 — 管理多个着色器预设 (A/B/C/D) 并可运行时切换。
 *
 * <p>每个 {@link ShaderPreset} 封装一套着色器程序 + 对应 RenderType。
 * 下游模组注册预设后，通过 {@link #setActivePreset} 切换当前使用的效果。</p>
 *
 * <h3>使用示例</h3>
 * <pre>{@code
 * // 注册预设
 * ShaderManager.registerPreset("cosmic", new ShaderDescriptor(
 *     "yizmodqzk", "rendertype_cosmic", "rendertype_cosmic_armor", true
 * ));
 * ShaderManager.registerPreset("ember", new ShaderDescriptor(
 *     "yizmodqzk", "rendertype_ember", null, false
 * ));
 *
 * // 切换
 * ShaderManager.setActivePreset("cosmic");
 *
 * // 物品/盔甲判定
 * ShaderManager.registerItemPredicate(stack -> stack.is(STAR_VOID.get()));
 * ShaderManager.registerArmorPredicate(stack -> hasStarBody(...));
 * }</pre>
 */
// 大白话: 特效管理方法
public final class ShaderManager extends RenderType {

    private static final Logger LOGGER = LoggerFactory.getLogger(ShaderManager.class);
    private static final VertexFormat VERTEX_FORMAT = DefaultVertexFormat.NEW_ENTITY;

    private ShaderManager(String name, VertexFormat format, VertexFormat.Mode mode,
                          int bufferSize, boolean affectsCrumbling, boolean sortOnUpload,
                          Runnable setupState, Runnable clearState) {
        super(name, format, mode, bufferSize, affectsCrumbling, sortOnUpload, setupState, clearState);
    }

    // ──────────────────────────────────────────────────────────────────
    //  预设注册表
    // ──────────────────────────────────────────────────────────────────

    /** 所有已注册的预设 */
    private static final Map<String, ShaderPreset> PRESETS = new ConcurrentHashMap<>();

    /** 当前激活的预设名 */
    private static volatile String activePresetName;

    /** 物品判定谓词 */
    private static final List<Predicate<ItemStack>> ITEM_PREDICATES = new CopyOnWriteArrayList<>();

    /** 盔甲判定谓词 */
    private static final List<Predicate<ItemStack>> ARMOR_PREDICATES = new CopyOnWriteArrayList<>();

    /** cosmic 图标 UV 坐标（10 个图标 × 4 分量） */
    private static final float[] COSMIC_UVS = new float[40];

    // ──────────────────────────────────────────────────────────────────
    //  公开 API
    // ──────────────────────────────────────────────────────────────────

    /**
     * 注册一个着色器预设。着色器文件位于:
     * {@code assets/<namespace>/shaders/core/<path>.json/.vsh/.fsh}
     *
     * @param name     预设名称（如 "cosmic", "ember"）
     * @param desc     着色器描述
     */
    public static void registerPreset(String name, ShaderDescriptor desc) {
        if (PRESETS.containsKey(name)) {
            LOGGER.warn("Shader preset already registered: {}", name);
            return;
        }
        PRESETS.put(name, new ShaderPreset(name, desc));
        LOGGER.info("Shader preset registered: {}", name);
        if (activePresetName == null) {
            activePresetName = name;
        }
    }

    /** 切换当前激活的预设 */
    public static void setActivePreset(String name) {
        if (!PRESETS.containsKey(name)) {
            LOGGER.warn("Unknown shader preset: {}", name);
            return;
        }
        activePresetName = name;
        LOGGER.info("Active shader preset switched to: {}", name);
    }

    /** 获取当前激活的预设名 */
    public static String getActivePresetName() {
        return activePresetName;
    }

    /** 注册物品谓词 */
    public static void registerItemPredicate(Predicate<ItemStack> predicate) {
        ITEM_PREDICATES.add(predicate);
    }

    /** 注册盔甲谓词 */
    public static void registerArmorPredicate(Predicate<ItemStack> predicate) {
        ARMOR_PREDICATES.add(predicate);
    }

    /** 设置 cosmic 图标 UV */
    public static void setCosmicUVs(float[] uvs) {
        System.arraycopy(uvs, 0, COSMIC_UVS, 0, 40);
    }

    /** 将 UV 写入着色器的 cosmicuvs uniform（供 Mixin 调用） */
    public static void applyCosmicUVs(ShaderInstance shader) {
        if (shader == null) return;
        var uCosmicUVs = shader.getUniform("cosmicuvs");
        if (uCosmicUVs == null) return;
        uCosmicUVs.set(COSMIC_UVS);
    }

    /** 判断物品是否应用着色器效果 */
    public static boolean hasItemEffect(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        for (var p : ITEM_PREDICATES) {
            if (p.test(stack)) return true;
        }
        return false;
    }

    /** 判断盔甲是否应用着色器效果 */
    public static boolean hasArmorEffect(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        for (var p : ARMOR_PREDICATES) {
            if (p.test(stack)) return true;
        }
        return false;
    }

    // ──────────────────────────────────────────────────────────────────
    //  RenderType 获取
    // ──────────────────────────────────────────────────────────────────

    /** 获取当前激活预设的物品 RenderType。无激活预设时返回 null，调用方应回退到原版渲染。 */
    public static RenderType getItemRenderType() {
        ShaderPreset preset = getActivePreset();
        if (preset == null || preset.starGlint == null) return null;
        return preset.starGlint;
    }

    /** 获取当前激活预设的 GUI RenderType。无激活预设时返回 null。 */
    public static RenderType getItemGuiRenderType() {
        ShaderPreset preset = getActivePreset();
        if (preset == null || preset.starGlint == null) return null;
        return preset.starGlint;
    }

    /** 获取当前激活预设的第一人称 RenderType。无激活预设时返回 null。 */
    public static RenderType getItemDirectRenderType() {
        ShaderPreset preset = getActivePreset();
        if (preset == null || preset.starGlintDirect == null) return null;
        return preset.starGlintDirect;
    }

    /** 获取当前激活预设的实体 RenderType。无激活预设时返回 null。 */
    public static RenderType getItemEntityRenderType() {
        ShaderPreset preset = getActivePreset();
        if (preset == null || preset.starEntityGlint == null) return null;
        return preset.starEntityGlint;
    }

    /** 获取当前激活预设的盔甲 RenderType。无激活预设时返回 null。 */
    public static RenderType getArmorRenderType() {
        ShaderPreset preset = getActivePreset();
        if (preset == null || preset.starArmorGlint == null) return null;
        return preset.starArmorGlint;
    }

    /** 获取当前激活预设的物品着色器 */
    public static ShaderInstance getActiveItemShader() {
        ShaderPreset preset = getActivePreset();
        return preset != null ? preset.itemShader : null;
    }

    /** 获取当前激活预设的盔甲着色器 */
    public static ShaderInstance getActiveArmorShader() {
        ShaderPreset preset = getActivePreset();
        return preset != null ? preset.armorShader : null;
    }

    // ──────────────────────────────────────────────────────────────────
    //  事件处理（由 tizModClient 订阅）
    // ──────────────────────────────────────────────────────────────────

    public static void onRegisterShaders(RegisterShadersEvent event) {
        ShaderEnvironmentAPI.ensureShaderCompatibility();
        for (Map.Entry<String, ShaderPreset> entry : PRESETS.entrySet()) {
            String name = entry.getKey();
            ShaderPreset preset = entry.getValue();
            ShaderDescriptor desc = preset.descriptor;

            // 物品着色器
            try {
                var id = ResourceLocation.fromNamespaceAndPath(desc.namespace, desc.itemShaderPath);
                var shader = new ShaderInstance(event.getResourceProvider(), id, VERTEX_FORMAT);
                event.registerShader(shader, instance -> {
                    preset.itemShader = instance;
                    initPresetRenderTypes(preset);
                    LOGGER.info("Shader preset [{}] item loaded", name);
                });
            } catch (IOException e) {
                LOGGER.error("Failed to load preset [{}] item shader: {}", name, e.getMessage());
            }

            // 盔甲着色器（可选）
            if (desc.armorShaderPath != null) {
                try {
                    var id = ResourceLocation.fromNamespaceAndPath(desc.namespace, desc.armorShaderPath);
                    var armorShader = new ShaderInstance(event.getResourceProvider(), id, VERTEX_FORMAT);
                    event.registerShader(armorShader, instance -> {
                        preset.armorShader = instance;
                        initPresetArmorRenderType(preset);
                        LOGGER.info("Shader preset [{}] armor loaded", name);
                    });
                } catch (IOException e) {
                    LOGGER.error("Failed to load preset [{}] armor shader: {}", name, e.getMessage());
                }
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────
    //  内部方法
    // ──────────────────────────────────────────────────────────────────

    private static ShaderPreset getActivePreset() {
        return activePresetName != null ? PRESETS.get(activePresetName) : null;
    }

    private static void initPresetRenderTypes(ShaderPreset preset) {
        ShaderStateShard shaderState = new ShaderStateShard(() -> preset.itemShader);
        TextureStateShard texture = new TextureStateShard(TextureAtlas.LOCATION_BLOCKS, false, false);
        TransparencyStateShard filmTrans = filmTransparency();
        DepthTestStateShard filmDepth = filmDepth();

        preset.starGlint = create("shader_" + preset.name,
                VERTEX_FORMAT, VertexFormat.Mode.QUADS, 1536, false, false,
                CompositeState.builder()
                        .setShaderState(shaderState).setTextureState(texture)
                        .setWriteMaskState(COLOR_WRITE).setCullState(NO_CULL)
                        .setDepthTestState(filmDepth).setTransparencyState(filmTrans)
                        .createCompositeState(false));

        preset.starGlintDirect = create("shader_" + preset.name + "_direct",
                VERTEX_FORMAT, VertexFormat.Mode.QUADS, 1536, false, false,
                CompositeState.builder()
                        .setShaderState(shaderState).setTextureState(texture)
                        .setWriteMaskState(COLOR_WRITE).setCullState(NO_CULL)
                        .setDepthTestState(filmDepth).setTransparencyState(filmTrans)
                        .createCompositeState(false));

        preset.starEntityGlint = create("shader_" + preset.name + "_entity",
                VERTEX_FORMAT, VertexFormat.Mode.QUADS, 1536, false, false,
                CompositeState.builder()
                        .setShaderState(shaderState).setTextureState(texture)
                        .setWriteMaskState(COLOR_WRITE).setCullState(NO_CULL)
                        .setDepthTestState(filmDepth).setTransparencyState(filmTrans)
                        .setOutputState(ITEM_ENTITY_TARGET)
                        .createCompositeState(false));

        LOGGER.info("Shader preset [{}] render types created", preset.name);
    }

    private static void initPresetArmorRenderType(ShaderPreset preset) {
        ShaderStateShard shaderState = new ShaderStateShard(() -> preset.armorShader);
        DepthTestStateShard filmDepth = filmDepth();

        // 盔甲使用标准 alpha 混合 → 黑底可以遮蔽原盔甲色
        // COLOR_DEPTH_WRITE 确保模型深度写入 → 避免 Redirect 模式下部件深度测试失败
        preset.starArmorGlint = create("shader_" + preset.name + "_armor",
                VERTEX_FORMAT, VertexFormat.Mode.QUADS, 1536, false, false,
                CompositeState.builder()
                        .setShaderState(shaderState)
                        .setWriteMaskState(COLOR_DEPTH_WRITE).setCullState(NO_CULL)
                        .setDepthTestState(filmDepth).setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                        .setLayeringState(VIEW_OFFSET_Z_LAYERING).setOutputState(ITEM_ENTITY_TARGET)
                        .createCompositeState(false));

        LOGGER.info("Shader preset [{}] armor render type created", preset.name);
    }

    /**
     * 为非 z 系列 TAIL 叠加创建 RenderType。使用 EQUAL 深度测试，
     * 确保星空只渲染在原版盔甲已写入深度的区域（盔甲纹理 cutout 自动生效）。
     */
    public static RenderType getArmorStarOverlayType() {
        ShaderPreset preset = getActivePreset();
        if (preset == null || preset.armorShader == null) return null;

        ShaderStateShard shaderState = new ShaderStateShard(() -> preset.armorShader);
        TextureStateShard texture = new TextureStateShard(TextureAtlas.LOCATION_BLOCKS, false, false);

        return create("shader_" + preset.name + "_armor_overlay",
                VERTEX_FORMAT, VertexFormat.Mode.QUADS, 1536, false, false,
                CompositeState.builder()
                        .setShaderState(shaderState).setTextureState(texture)
                        .setWriteMaskState(COLOR_WRITE).setCullState(NO_CULL)
                        .setDepthTestState(EQUAL_DEPTH_TEST).setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                        .setOutputState(ITEM_ENTITY_TARGET)
                        .createCompositeState(false));
    }

    /**
     * 体表闪电表面 RenderType — NEW_ENTITY 格式(含 Normal) + 加法混合 + EQUAL 深度贴附模型表面。
     * 由 LightningShaders 注册 surface shader 后调用，shader 通过 Supplier 注入。
     */
    public static net.minecraft.client.renderer.RenderType createLightningSurfaceType(
            java.util.function.Supplier<net.minecraft.client.renderer.ShaderInstance> shader) {
        ShaderStateShard shaderState = new ShaderStateShard(shader);
        TransparencyStateShard additive = new TransparencyStateShard("lightning_additive",
                () -> {
                    com.mojang.blaze3d.systems.RenderSystem.enableBlend();
                    com.mojang.blaze3d.systems.RenderSystem.blendFuncSeparate(
                            com.mojang.blaze3d.platform.GlStateManager.SourceFactor.SRC_ALPHA,
                            com.mojang.blaze3d.platform.GlStateManager.DestFactor.ONE,
                            com.mojang.blaze3d.platform.GlStateManager.SourceFactor.ONE,
                            com.mojang.blaze3d.platform.GlStateManager.DestFactor.ONE);
                },
                () -> {
                    com.mojang.blaze3d.systems.RenderSystem.disableBlend();
                    com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
                });
        return create("lightning_surface",
                VERTEX_FORMAT,
                com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS, 1536, false, false,
                CompositeState.builder()
                        .setShaderState(shaderState)
                        .setWriteMaskState(COLOR_WRITE).setCullState(NO_CULL)
                        .setDepthTestState(new DepthTestStateShard("<=", 515)).setTransparencyState(additive)
                        .setOutputState(ITEM_ENTITY_TARGET)
                        .createCompositeState(false));
    }

    // 共享的 RenderStateShard 工厂
    private static TransparencyStateShard filmTransparency() {
        return new TransparencyStateShard("film_trans",
                () -> {
                    com.mojang.blaze3d.systems.RenderSystem.enableBlend();
                    com.mojang.blaze3d.systems.RenderSystem.blendFuncSeparate(
                            com.mojang.blaze3d.platform.GlStateManager.SourceFactor.SRC_COLOR,
                            com.mojang.blaze3d.platform.GlStateManager.DestFactor.ONE,
                            com.mojang.blaze3d.platform.GlStateManager.SourceFactor.ZERO,
                            com.mojang.blaze3d.platform.GlStateManager.DestFactor.ONE);
                },
                () -> {
                    com.mojang.blaze3d.systems.RenderSystem.disableBlend();
                    com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
                });
    }

    private static DepthTestStateShard filmDepth() {
        return new DepthTestStateShard("<=", 515);
    }

    // ──────────────────────────────────────────────────────────────────
    //  数据类型
    // ──────────────────────────────────────────────────────────────────

    /** 着色器预设描述符（下游模组注册时传入） */
    public record ShaderDescriptor(
            String namespace,           // modid
            String itemShaderPath,      // shader path e.g. "rendertype_cosmic"
            String armorShaderPath,     // null = no separate armor shader
            boolean useBlockAtlas       // true = bind TextureAtlas.LOCATION_BLOCKS
    ) {}

    /** 着色器预设实例（由 ShaderManager 管理） */
    static class ShaderPreset {
        final String name;
        final ShaderDescriptor descriptor;
        ShaderInstance itemShader;
        ShaderInstance armorShader;
        volatile RenderType starGlint;
        volatile RenderType starGlintDirect;
        volatile RenderType starEntityGlint;
        volatile RenderType starArmorGlint;

        ShaderPreset(String name, ShaderDescriptor desc) {
            this.name = name;
            this.descriptor = desc;
        }
    }
}
