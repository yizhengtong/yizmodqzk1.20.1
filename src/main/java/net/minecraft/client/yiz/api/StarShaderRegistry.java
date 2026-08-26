package net.minecraft.client.yiz.api;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.world.item.ItemStack;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.event.RegisterShadersEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

import static net.minecraft.client.renderer.RenderStateShard.*;

/**
 * 星空着色器注册表 — 着色器渲染对接 API（A层→B层桥梁）
 *
 * <p>前置库提供星空着色器渲染能力，下游模组通过此 API 通知前置库
 * "哪些物品/盔甲需要星空叠加效果"。</p>
 *
 * <h3>使用示例（下游模组）</h3>
 * <pre>{@code
 * // 在客户端入口注册谓词
 * StarShaderRegistry.registerStarItem(stack ->
 *     stack.is(StarVoidItem.ITEM.get())
 * );
 * StarShaderRegistry.registerStarArmor(stack -> {
 *     Player player = Minecraft.getInstance().player;
 *     return player != null && hasStarBody(player);
 * });
 * }</pre>
 *
 * <h3>渲染流程</h3>
 * 下游注册谓词 → {@link RegisterShadersEvent} 触发时注册着色器 + 创建 RenderType
 * → {@link net.minecraft.client.yiz.mixin.ItemRendererStarMixin} 在物品渲染时调用
 * {@link #hasStarEffect} 判断并叠加渲染
 */
// 大白话: 星芒方法
public final class StarShaderRegistry extends RenderType {

    private static final Logger LOGGER = LoggerFactory.getLogger(StarShaderRegistry.class);

    private StarShaderRegistry(String name, VertexFormat format, VertexFormat.Mode mode,
                               int bufferSize, boolean affectsCrumbling, boolean sortOnUpload,
                               Runnable setupState, Runnable clearState) {
        super(name, format, mode, bufferSize, affectsCrumbling, sortOnUpload, setupState, clearState);
    }

    // ─────────────────────────────────────────────────────────────────
    //  着色器元信息
    // ─────────────────────────────────────────────────────────────────

    private static final ResourceLocation SHADER_ID = ResourceLocation.fromNamespaceAndPath("yizmodqzk", "rendertype_star_glint");
    private static final ResourceLocation SHADER_ID_ARMOR = ResourceLocation.fromNamespaceAndPath("yizmodqzk", "rendertype_star_glint_armor");
    private static final VertexFormat VERTEX_FORMAT = DefaultVertexFormat.NEW_ENTITY;

    // ─────────────────────────────────────────────────────────────────
    //  谓词注册表（下游模组通过 API 注册）
    // ─────────────────────────────────────────────────────────────────

    private static final List<Predicate<ItemStack>> ITEM_PREDICATES = new CopyOnWriteArrayList<>();
    private static final List<Predicate<ItemStack>> ARMOR_PREDICATES = new CopyOnWriteArrayList<>();

    /**
     * 注册一个物品谓词：匹配的物品在渲染时叠加星空效果。
     */
    public static void registerStarItem(Predicate<ItemStack> predicate) {
        if (predicate == null) throw new IllegalArgumentException("predicate must not be null");
        ITEM_PREDICATES.add(predicate);
        LOGGER.debug("Star item predicate registered (total: {})", ITEM_PREDICATES.size());
    }

    /**
     * 注册一个盔甲谓词：匹配的盔甲在实体上渲染时叠加星空效果。
     */
    public static void registerStarArmor(Predicate<ItemStack> predicate) {
        if (predicate == null) throw new IllegalArgumentException("predicate must not be null");
        ARMOR_PREDICATES.add(predicate);
        LOGGER.debug("Star armor predicate registered (total: {})", ARMOR_PREDICATES.size());
    }

    /**
     * 供 Mixin 判断物品是否需要星空叠加。
     */
    public static boolean hasStarEffect(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        for (Predicate<ItemStack> p : ITEM_PREDICATES) {
            if (p.test(stack)) return true;
        }
        return false;
    }

    /**
     * 供 Mixin 判断盔甲是否需要星空叠加。
     */
    public static boolean hasStarArmorEffect(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        for (Predicate<ItemStack> p : ARMOR_PREDICATES) {
            if (p.test(stack)) return true;
        }
        return false;
    }

    // ─────────────────────────────────────────────────────────────────
    //  着色器引用 + RenderType 延迟初始化
    // ─────────────────────────────────────────────────────────────────

    private static ShaderInstance starShader;
    private static ShaderInstance starArmorShader;

    private static volatile RenderType starGlint;
    private static volatile RenderType starGlintDirect;
    private static volatile RenderType starEntityGlint;
    private static volatile RenderType starArmorGlint;

    public static ShaderInstance getStarShader() {
        return starShader;
    }

    public static ShaderInstance getStarArmorShader() {
        return starArmorShader;
    }

    public static RenderType starGlint() {
        RenderType rt = starGlint;
        if (rt == null) throw new IllegalStateException("Star shader not yet registered");
        return rt;
    }

    public static RenderType starGlintDirect() {
        RenderType rt = starGlintDirect;
        if (rt == null) throw new IllegalStateException("Star shader not yet registered");
        return rt;
    }

    public static RenderType starEntityGlint() {
        RenderType rt = starEntityGlint;
        if (rt == null) throw new IllegalStateException("Star shader not yet registered");
        return rt;
    }

    public static RenderType starArmorGlint() {
        RenderType rt = starArmorGlint;
        if (rt == null) throw new IllegalStateException("Star shader not yet registered");
        return rt;
    }

    // ─────────────────────────────────────────────────────────────────
    //  RenderStateShard 常量（复刻原版光泽配置）
    // ─────────────────────────────────────────────────────────────────

    private static final ShaderStateShard STAR_GLINT_SHADER_STATE = new ShaderStateShard(
            () -> starShader
    );

    private static final ShaderStateShard STAR_ARMOR_SHADER_STATE = new ShaderStateShard(
            () -> starArmorShader
    );

    /** SRC_COLOR + ONE 叠加混合 → 原色变亮，暗色叠加星辉 */
    private static final TransparencyStateShard STAR_TRANSPARENCY = new TransparencyStateShard(
            "star_transparency",
            () -> {
                com.mojang.blaze3d.systems.RenderSystem.enableBlend();
                com.mojang.blaze3d.systems.RenderSystem.blendFuncSeparate(
                        com.mojang.blaze3d.platform.GlStateManager.SourceFactor.SRC_COLOR,
                        com.mojang.blaze3d.platform.GlStateManager.DestFactor.ONE,
                        com.mojang.blaze3d.platform.GlStateManager.SourceFactor.ZERO,
                        com.mojang.blaze3d.platform.GlStateManager.DestFactor.ONE
                );
            },
            () -> {
                com.mojang.blaze3d.systems.RenderSystem.disableBlend();
                com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
            }
    );

    /** LEQUAL: 在模型表面或更近处渲染（避免多通道浮点精度差异） */
    private static final DepthTestStateShard STAR_FILM_DEPTH = new DepthTestStateShard("<=", 515);

    /** SRC_COLOR + ONE 叠加混合 */
    private static final TransparencyStateShard STAR_FILM_TRANSPARENCY = new TransparencyStateShard(
            "star_film_transparency",
            () -> {
                com.mojang.blaze3d.systems.RenderSystem.enableBlend();
                com.mojang.blaze3d.systems.RenderSystem.blendFuncSeparate(
                        com.mojang.blaze3d.platform.GlStateManager.SourceFactor.SRC_COLOR,
                        com.mojang.blaze3d.platform.GlStateManager.DestFactor.ONE,
                        com.mojang.blaze3d.platform.GlStateManager.SourceFactor.ZERO,
                        com.mojang.blaze3d.platform.GlStateManager.DestFactor.ONE
                );
            },
            () -> {
                com.mojang.blaze3d.systems.RenderSystem.disableBlend();
                com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
            }
    );

    // TexturingState removed — star animation uses screen-space coordinates in GLSL

    // ─────────────────────────────────────────────────────────────────
    //  事件处理 — RegisterShadersEvent
    // ─────────────────────────────────────────────────────────────────

    /**
     * 由 {@link net.minecraft.client.yiz.tizModClient} 订阅 Mod 总线调用。
     * 在 {@link RegisterShadersEvent} 中注册星空着色器，
     * 着色器加载完成后初始化所有 RenderType。
     */
    public static void onRegisterShaders(RegisterShadersEvent event) {
        ShaderEnvironmentAPI.ensureShaderCompatibility();

        // 物品着色器
        try {
            ShaderInstance shader = new ShaderInstance(
                    event.getResourceProvider(), SHADER_ID, VERTEX_FORMAT
            );
            event.registerShader(shader, instance -> {
                starShader = instance;
                initItemRenderTypes();
                LOGGER.info("Star shader loaded and RenderTypes initialized");
            });
        } catch (IOException e) {
            LOGGER.error("Failed to load star shader '{}': {}", SHADER_ID, e.getMessage());
        }

        // 盔甲着色器
        try {
            ShaderInstance armorShader = new ShaderInstance(
                    event.getResourceProvider(), SHADER_ID_ARMOR, VERTEX_FORMAT
            );
            event.registerShader(armorShader, instance -> {
                starArmorShader = instance;
                initArmorRenderType();
                LOGGER.info("Star armor shader loaded");
            });
        } catch (IOException e) {
            LOGGER.error("Failed to load star armor shader '{}': {}", SHADER_ID_ARMOR, e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  内部方法
    // ─────────────────────────────────────────────────────────────────

    // setupStarTexturing removed — star animation is procedural in GLSL

    private static void initItemRenderTypes() {
        TextureStateShard baseTexture = new TextureStateShard(TextureAtlas.LOCATION_BLOCKS, false, false);

        starGlint = create("star_glint",
                DefaultVertexFormat.NEW_ENTITY,
                VertexFormat.Mode.QUADS, 1536, false, false,
                CompositeState.builder()
                        .setShaderState(STAR_GLINT_SHADER_STATE)
                        .setTextureState(baseTexture)
                        .setWriteMaskState(COLOR_WRITE)
                        .setCullState(NO_CULL)
                        .setDepthTestState(STAR_FILM_DEPTH)
                        .setTransparencyState(STAR_FILM_TRANSPARENCY)

                        .createCompositeState(false)
        );

        starGlintDirect = create("star_glint_direct",
                DefaultVertexFormat.NEW_ENTITY,
                VertexFormat.Mode.QUADS, 1536, false, false,
                CompositeState.builder()
                        .setShaderState(STAR_GLINT_SHADER_STATE)
                        .setTextureState(baseTexture)
                        .setWriteMaskState(COLOR_WRITE)
                        .setCullState(NO_CULL)
                        .setDepthTestState(STAR_FILM_DEPTH)
                        .setTransparencyState(STAR_FILM_TRANSPARENCY)

                        .createCompositeState(false)
        );

        starEntityGlint = create("star_entity_glint",
                DefaultVertexFormat.NEW_ENTITY,
                VertexFormat.Mode.QUADS, 1536, false, false,
                CompositeState.builder()
                        .setShaderState(STAR_GLINT_SHADER_STATE)
                        .setTextureState(baseTexture)
                        .setWriteMaskState(COLOR_WRITE)
                        .setCullState(NO_CULL)
                        .setDepthTestState(STAR_FILM_DEPTH)
                        .setTransparencyState(STAR_FILM_TRANSPARENCY)

                        .setOutputState(ITEM_ENTITY_TARGET)
                        .createCompositeState(false)
        );

        LOGGER.info("Star item RenderTypes created: glint/glint_direct/entity_glint");
    }

    private static void initArmorRenderType() {
        starArmorGlint = create("star_armor_glint",
                DefaultVertexFormat.NEW_ENTITY,
                VertexFormat.Mode.QUADS, 1536, false, false,
                CompositeState.builder()
                        .setShaderState(STAR_ARMOR_SHADER_STATE)
                        .setWriteMaskState(COLOR_WRITE)
                        .setCullState(NO_CULL)
                        .setDepthTestState(NO_DEPTH_TEST)
                        .setTransparencyState(STAR_FILM_TRANSPARENCY)
                        .setLayeringState(VIEW_OFFSET_Z_LAYERING)
                        .setOutputState(ITEM_ENTITY_TARGET)
                        .createCompositeState(false)
        );
        LOGGER.info("Star armor RenderType created");
    }
}
