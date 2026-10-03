package net.minecraft.client.yiz.client.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Map;

/**
 * 锁定描边 RenderType（RenderType 子类以访问 protected shard 常量）。
 * - fill(tex)：实体穿墙填充到专用 FBO（NO_DEPTH_TEST + 实体纹理 + FILL_TARGET 绑 FBO）
 * - post：全屏 quad 采样 FBO 做边缘检测 + 发光（KEEP_TARGET 保持当前渲染目标）
 *
 * <h2>⚠️ 第三轮黑屏回退：本文件曾经引入的两处"全局渲染状态"改动已全部撤销，别再写回来</h2>
 *
 * <p>2026-10-04 第二轮为了做"按贴图 alpha 镂空"，在这里加了两样东西：
 * {@code entityTextureState(tex)}（自带 {@code setFilter} + {@code setShaderTexture(0,…)} +
 * {@code ShaderInstance#setSampler("uEntityTex",…)}）与 {@code MASK_NO_BLEND}
 * （setup 与 clear <b>都</b> {@code disableBlend()}）；同时把
 * {@code rendertype_lock_fill.json} 的 {@code "blend"} 节点删掉。用户实测：<b>一进游戏就整屏黑</b>。
 * 这一版已整体回退成"上一版能跑的写法"：</p>
 * <ul>
 *   <li>贴图 shard 回到原版 {@link RenderStateShard.TextureStateShard}
 *       （{@code new TextureStateShard(tex, false, false)}）；</li>
 *   <li>透明状态回到原版 {@code NO_TRANSPARENCY}（setup 时 {@code disableBlend}，
 *       clear 是空实现，与其它 RenderType 完全同构）；</li>
 *   <li>JSON 恢复 {@code "blend"} 节点 ⇒ 回到 2/3 号黑屏档案里那条<b>必须遵守</b>的规则。</li>
 * </ul>
 *
 * <h2>🔴 黑屏的确切结构性根因（本工程第二次踩，务必读完再动手）</h2>
 *
 * <p><b>缺 {@code "blend"} 节点 = 让着色器程序自己把混合关掉</b>。链路（逐行可查）：</p>
 * <pre>
 * ① ShaderInstance#apply()                       ShaderInstance.java:337
 *        this.blend.apply();                    // ★ 每次 draw 都调，在 RenderType
 *                                               //   的 transparency shard 之后执行
 * ② ShaderInstance#parseBlendNode(null)          ShaderInstance.java:250-252
 *        return new BlendMode();                // JSON 没有 blend 节点时
 * ③ BlendMode()                                  BlendMode.java:31-33
 *        this(false, true, 1, 0, 1, 0, 32774);  // opaque = true
 * ④ BlendMode#apply()                            BlendMode.java:43-53
 *        if (!this.equals(lastApplied)) {
 *            if (lastApplied == null || this.opaque != lastApplied.isOpaque()) {
 *                lastApplied = this;
 *                if (this.opaque) { RenderSystem.disableBlend(); return; }  // ←★ 全局关混合
 *            }
 *            ...blendEquation / blendFunc...     // ←★ return 了，连 blendFunc 都没设
 *        }
 * </pre>
 * <p>两个后果都是"不会自愈"的那一类：</p>
 * <ol>
 *   <li><b>RenderType 刚打开的混合被着色器关掉</b>：shard 顺序是
 *       {@code [texture, shader, transparency, …]}（{@code RenderType.java:551}，setup/clear
 *       都<b>按顺序</b>执行，{@code RenderType.java:490-492}），而 {@code apply()} 在
 *       {@code BufferUploader/VertexBuffer} 的 draw 里、也就是<b>所有 shard 之后</b>才跑
 *       ⇒ 谁想开混合，都可能被自己这一句关掉。<b>这正是本工程 2/3 号闪电层整屏发黑的同一结构</b>
 *       （档案原文见 {@code LightningRenderer} 的"⚑ 黑屏档案"：缺 blend 节点 ⇒
 *       {@code new BlendMode()} ⇒ {@code opaque=true} ⇒ {@code disableBlend()} ⇒
 *       "覆盖写下去就是近黑"）。</li>
 *   <li><b>{@code BlendMode.lastApplied} 这个静态缓存会和 GL 真实状态长期不一致</b>：
 *       它<b>只在"不透明标志变化"时</b>才更新（{@code BlendMode.java:45}），
 *       而 {@code apply()} 又用 {@code equals(lastApplied)} 做短路。于是任何"绕过 BlendMode
 *       直接改混合"的代码（比如本轮那个 {@code MASK_NO_BLEND}，以及任何
 *       {@code RenderSystem.enableBlend()/disableBlend()}）都会让缓存说谎；之后某个 pass
 *       即使声明了非不透明混合，也可能因为 {@code equals} 命中而<b>整段跳过 enableBlend()</b> ⇒
 *       该 pass 在"混合被关"的状态下绘制，且下一帧还是这样。</li>
 * </ol>
 * <p><b>⇒ 两条硬规则</b>：</p>
 * <ol>
 *   <li>本 RenderType 的 JSON <b>必须</b>有 {@code "blend"} 节点，且它解析出来的
 *       {@code opaque} 必须为 {@code false}；</li>
 *   <li>本 RenderType <b>不准</b>自带"在 clear 里也 {@code disableBlend()}"的
 *       {@code TransparencyStateShard}（{@code MASK_NO_BLEND} 已删除）。要"不混合"，
 *       走原版 {@code NO_TRANSPARENCY}（与其它 RenderType 同构），或干脆在片元里解决。</li>
 * </ol>
 *
 * <h2>下一步要安全重做"按贴图 alpha 镂空"的路线（三条，按推荐顺序）</h2>
 *
 * <p><b>2026-10-05 更新：第 1 + 3 条已落地</b>（{@code LockOutlineLayerTexture} 逐层贴图 +
 * {@code LockOutlineAlphaMask} 读 α 位图 + {@code LockOutlineFillConsumer} 按面丢/切子面），
 * 本文件<b>一个字节都没改</b>：JSON 的 {@code "blend"} 节点还在、透明状态还是原版 {@code NO_TRANSPARENCY}、
 * 贴图还是原版 {@code TextureStateShard}（只是传进来的 {@code texture} 参数从"渲染器主贴图"变成了
 * "该层自己的贴图"，由调用点 {@code LockOutlineBufferSource#getBuffer} 决定）。
 * 于是第 2 条（自带采样器）<b>不需要</b>再做了：CPU 侧已经能把填充几何切成"只覆盖不透明纹素"的子面。</p>
 * <ol>
 *   <li><b>CPU 侧过滤顶点（最推荐，零渲染状态风险）—— 已实现</b>：
 *       {@code LockOutlineFillConsumer} 逐顶点拿到 {@code uv}，配合 {@code LockOutlineLayerTexture}
 *       给出的"这一层用哪张贴图"和 {@code LockOutlineAlphaMask} 给出的 CPU α 位图，
 *       把"UV 覆盖的纹素全透明"的面<b>整面丢掉</b>、把"半透明半不透明"的面<b>按纹素连通段切成子面</b>。
 *       一个采样器、一个混合状态都不用碰。</li>
 *   <li><b>本 RenderType 自带采样器（次选）</b>：如果一定要在 GPU 做 discard，正确做法是
 *       给 fill 单独开一个取样器名字（例如 {@code uEntityTex}）+ 在<b>同一个 setup 里</b>绑定，
 *       但<b>必须同时满足</b>：JSON 保留 {@code "blend"} 节点（{@code opaque=false}）、
 *       不给本 RenderType 加任何"关混合"的自定义 shard、且<b>绝不</b>调全局
 *       {@code RenderSystem.disableBlend()}。上一轮失败的原因不是"改名"这件事本身，
 *       而是把改名和"删 blend 节点 + MASK_NO_BLEND"打包在一起做。
 *       <b>现状：不做</b>。填充 RenderType 的贴图已经逐层正确，
 *       {@code Sampler0} 采到的就是它（见 {@code LockOutlineBufferSource} 类注释），
 *       fsh 里那句 {@code discard} 由它自己决定生效与否，CPU 侧不依赖它。</li>
 *   <li><b>逐层贴图（无论走 1 还是 2 都需要）—— 已实现</b>：见 {@link LockOutlineLayerTexture}，
 *       反射（按字段类型，生产环境 SRG 改名也成立）优先、{@code RenderType#toString()} 正则兜底，
 *       都失败就回退渲染器主贴图（= 现状）。</li>
 * </ol>
 */
public class LockOutlineRenderType extends RenderType {

    /** 保持当前渲染目标（后处理用：保持主缓冲，不被默认 MAIN_TARGET 切走）。 */
    private static final RenderStateShard.OutputStateShard KEEP_TARGET =
        new RenderStateShard.OutputStateShard("keep_target", () -> {}, () -> {});

    /** 填充时切换到专用 FBO（fill flush 时绑定）；clear 切回主缓冲—— fill buffer 若中途自动刷帧
     *  （BufferBuilder 满触发 immediate.endBatch）也必须切回主缓冲，否则后续实体本体 normal 渲染
     *  写进 FBO 而非主缓冲 → 实体本体不渲染、只剩描边（描边有但实体消失）。
     *
     *  <p><b>两处都必须是"显式且带 viewport"的</b>：setup 用
     *  {@link LockOutlineFramebuffer#bindForFill()}（{@code bindWrite(true)} —— 描边 FBO 的
     *  viewport 必须按 FBO 尺寸设，否则 3D 填充几何会落在错误的裁剪区）；clear 用
     *  {@code getMainRenderTarget().bindWrite(true)}（{@code RenderTarget.clear()} 会把 viewport
     *  改成描边 FBO 的尺寸并停在 0 号帧缓冲上，见 {@code LockOutlineFramebuffer} 类注释）。</p> */
    private static final RenderStateShard.OutputStateShard FILL_TARGET =
        new RenderStateShard.OutputStateShard("lock_fill_target",
            () -> LockOutlineRenderer.framebuffer().bindForFill(),
            () -> LockOutlineFramebuffer.bindMain(net.minecraft.client.Minecraft.getInstance()));

    /** 按实体纹理缓存 fill RenderType（实体纹理数量有限，避免每帧新建）。 */
    private static final Map<ResourceLocation, RenderType> FILL_CACHE = new HashMap<>();

    private LockOutlineRenderType(String name, VertexFormat format, VertexFormat.Mode mode,
                                  int bufferSize, boolean affectsCrumbling, boolean sortOnUpload,
                                  Runnable setupTask, Runnable clearTask) {
        super(name, format, mode, bufferSize, affectsCrumbling, sortOnUpload, setupTask, clearTask);
    }

    /** 填充 RenderType：带实体纹理（fill fsh 采样 alpha 过滤镂空区）+ 穿墙 + 绑 FBO。
     *
     *  <p><b>这里是第三轮回退的那一处</b>：第二轮用的是自定义
     *  {@code entityTextureState(tex)}（额外做 {@code setFilter} + {@code setSampler} +
     *  设 {@code uCutout} uniform），已删除，回到原版 {@link RenderStateShard.TextureStateShard}。
     *  理由与"要重做镂空时该怎么做"写在类注释的两条硬规则 + 三条路线里。</p> */
    public static RenderType fill(ResourceLocation texture) {
        return FILL_CACHE.computeIfAbsent(texture, LockOutlineRenderType::createFill);
    }

    private static RenderType createFill(ResourceLocation texture) {
        return RenderType.create("yizxian_lock_fill",
            DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS,
            256, false, false,
            CompositeState.builder()
                .setShaderState(new ShaderStateShard(LockOutlineShaders::getLockFill))
                .setTextureState(new RenderStateShard.TextureStateShard(texture, false, false))
                // ★ NO_TRANSPARENCY（原版常量）= setup 时 disableBlend、clear 空实现。
                //   绝不要换成自定义的"clear 里也 disableBlend"的 shard（第二轮那样做过 ⇒ 黑屏，
                //   理由见类注释第 2 条硬规则：绕过 BlendMode 直接改混合会让它的 lastApplied 缓存说谎）。
                .setTransparencyState(NO_TRANSPARENCY)
                .setCullState(NO_CULL)
                .setDepthTestState(NO_DEPTH_TEST)
                .setOutputState(FILL_TARGET)
                .setWriteMaskState(COLOR_WRITE)
                .createCompositeState(false));
    }

    /** 后处理 RenderType：全屏 quad 采样 FBO 纹理，半透明叠加描边。 */
    public static RenderType post() {
        return RenderType.create("yizxian_lock_post",
            DefaultVertexFormat.POSITION_TEX, VertexFormat.Mode.QUADS,
            256, false, false,
            CompositeState.builder()
                .setShaderState(new ShaderStateShard(LockOutlineShaders::getLockPost))
                .setTextureState(NO_TEXTURE)
                .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                .setCullState(NO_CULL)
                .setDepthTestState(NO_DEPTH_TEST)
                .setOutputState(KEEP_TARGET)
                .setWriteMaskState(COLOR_WRITE)
                .createCompositeState(false));
    }
}
