package net.minecraft.client.yiz.client.render;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexMultiConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.yiz.api.OutlineRegistry;
import net.minecraft.client.yiz.modelmirror.MirrorDiag;
import net.minecraft.client.yiz.modelmirror.ModelMirrorBufferSource;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 锁定描边双写缓冲（复刻 vanilla {@code OutlineBufferSource}）：
 * 当前渲染实体需要描边时，一次渲染同时写主缓冲（正常显示）+ 填充 FBO（穿墙纯色）。
 * fill RenderType 带实体纹理（纹理过滤）+ 绑 {@link LockOutlineFramebuffer}。
 * 由 {@code EntityRenderDispatcherOutlineMixin} 在实体渲染时 {@link #setCurrent} 驱动。
 *
 * <p><b>与 vanilla 的对齐，以及"谁能进填充 FBO"的三道门禁</b>（都很关键，理由写在各处）：</p>
 * <ol>
 *   <li><b>门禁 1</b>（= vanilla 的唯一判据）：只双写 {@code rt.outline().isPresent()}
 *       （= affects_outline）的 RenderType：影子/眼睛/贴花/充能漩涡等 NONE 层不能进填充 FBO，
 *       否则描边会沿着"地上的影子方块"等非体表几何发光。2026-10-04 的现场日志已实证它在生效
 *       （{@code entity_shadow} 与 {@code lightning_surface} 都是 {@code outlineProperty=none}，都被挡下）；</li>
 *   <li><b>门禁 2</b>：影子 pass 的身份判据（与模型几何镜像共用 {@code ModelMirrorBufferSource#isShadowPass}），
 *       纯兜底；地面/水/地形永远拿不到这个包装（它只交给 {@code EntityRenderer.render}），
 *       物品/盔甲则<b>故意不排除</b>（它们是目标自身外观的一部分），理由写在 {@link #getBuffer} 里；</li>
 *   <li>fill consumer 的 {@code defaultColor}/{@code unsetDefaultColor} 是空实现（挡 {@code VertexMultiConsumer}
 *       的广播覆盖），描边色/透明度<b>只能</b>由构造器那一次 {@code super.defaultColor(...)} 设定 ——
 *       这正是"描边色与充能进度（alpha）双双失效、填充变成不透明白色"那个 bug 的所在，见该构造器注释；</li>
 *   <li>fill 只在 {@code MultiBufferSource.BufferSource} 语义下攒批，flush 由
 *       {@link #endFillBatch()} 驱动（fill RenderType 的 FILL_TARGET 负责"绑 FBO/交还主目标"）。</li>
 * </ol>
 *
 * <p><b>填充的语义（决定了后处理看到什么）—— 第四轮：CPU 侧"按贴图 alpha 镂空"已实现</b>：
 * 填充不再是"本体几何的外壳"，而是<b>该层贴图里真正有像素的那部分表面</b>：
 * 整面 UV 覆盖的纹素全透明 ⇒ 这个面不填（"无纹理部位被整体描边"就是这么消失的）；
 * 面的 UV 里既有透明又有不透明 ⇒ 按不透明纹素的连通段把面切成若干子面填。
 * 判据全部来自 CPU 侧的 alpha 位图（{@link LockOutlineAlphaMask}），实现见
 * {@link LockOutlineFillConsumer} 与 {@link LockOutlineLayerTexture} 的类注释。</p>
 *
 * <p><b>关于 fsh 里那句 {@code if (tex.a < 0.1) discard;}</b>（本轮<b>未</b>改动着色器，一个字节都没动）：</p>
 * <ol>
 *   <li>它采到的贴图 = <b>fill RenderType 自己带的贴图</b>：{@code RenderType.setupRenderState()} 的
 *       {@code TextureStateShard} 会 {@code setShaderTexture(0, tex)}（{@code RenderStateShard.java:517-527}），
 *       紧接着 {@code BufferUploader._drawWithShader} → {@code VertexBuffer._drawWithShader} 用
 *       {@code RenderSystem.getShaderTexture(0)} 把 {@code Sampler0} 绑到那个纹理单元
 *       （{@code VertexBuffer.java:123-127}），{@code ShaderInstance.apply()} 再按 JSON 的 samplers 顺序
 *       上传纹理单元号并绑定该纹理（{@code ShaderInstance.java:333-365}）。</li>
 *   <li>所以历史上它"看起来是空操作"的真凶是<b>各层共用渲染器主贴图</b>：UV 属于各自那层，
 *       贴图却是主贴图 ⇒ 采到别人 UV 的不透明区 ⇒ alpha≈1 ⇒ 永不 discard。
 *       本轮把 fill 的贴图改成"该层自己的贴图"（{@link LockOutlineLayerTexture}）后，
 *       这一条至少在"UV 与贴图同源"上成立了；<b>但它是否真在运行期生效，本轮无法在不开客户端的前提下断言</b>
 *       （所以镂空判据不依赖它：CPU 侧已经把面和子面切好了，它生效只是再切一遍边角，
 *       不生效也不影响镂空的最终形状）。</li>
 *   <li>硬约束照旧：<b>不准</b>为了"让 discard 生效"去删 JSON 的 {@code "blend"} 节点、
 *       抢 {@code Sampler0} 名字、或在 RenderType 里 {@code shader.setSampler(...)}。</li>
 * </ol>
 *
 * <p><b>历史证据（第三轮现场日志）</b>：掩码回读的"实心度"长期在 93~99%
 * （{@code [FxBlack] 描边 FBO 回读 … 实心度=93.4% / 99.3%}）—— 那是"整个四边形都填"的直接后果。
 * 本轮起同一条回读应明显下降，另外日志里会多出
 * {@code [LockOutline] 按贴图 alpha 镂空（CPU 侧…）：本帧面=… 丢弃整面=… 切成子面=…} 这条硬证据。</p>
 *
 * <p><b>还能做的诊断</b>：{@code /yiz fxtoggle probe} 会（在下一帧的 lock-post 阶段）
 * 回读描边 FBO 并打印掩码统计（填充点占屏幕 / 实心度 / 邻域覆盖率 / ★后处理涂色占比）。
 * 第三轮起它<b>不再翻转任何开关</b>（A/B 翻转已随镂空一起回退，见 {@code RenderTargetProbe}）。</p>
 */
public final class LockOutlineBufferSource implements MultiBufferSource {

    private static final Logger LOGGER = LoggerFactory.getLogger("YizModelMirror");

    private static LockOutlineBufferSource INSTANCE;

    /** 本帧是否渲染过任意描边实体（星级/渴攻等任意 Provider 返回非 null 即置位）：
     *  供 LockOutlineRenderer 后处理判断，实现星级描边无条件渲染（不依赖渴攻锁定/充能）。 */
    private static boolean hasAnyOutline;

    // fill buffer 容量加大：复杂实体（GeckoLib 辖界者等）顶点上千，256 初始容量会在实体渲染中途
    // 自动 endBatch → FILL_TARGET 绑 FBO 刷帧（配 clearTask 切回主缓冲双保险）。
    // （BufferBuilder 构造器内部还会 ×6，必要时按 2MB 步长自增长，见 BufferBuilder.java:41-58 ——
    //  "按贴图 alpha 镂空"会把一个面切成多个子面，顶点数变多，容量这条不再需要人肉估算。）
    private final BufferBuilder fillBuilder = new BufferBuilder(65536);
    private final MultiBufferSource.BufferSource fillBufferSource = MultiBufferSource.immediate(fillBuilder);

    /**
     * 本帧"攒了半个面"的 fill 消费者（按创建顺序）。
     *
     * <p>按贴图 alpha 镂空必须<b>整面</b>决定留/丢（半个面会让后面所有面错位），所以
     * {@link LockOutlineFillConsumer} 会把每 4 个顶点攒成一个面再写出去；留下不满 4 个顶点的"零头"
     * 只可能来自非 4 倍数的异常几何，这里在"换层"（换 fill RenderType 会触发上一次 draw）和"刷帧"前
     * 把它写回它自己的那个批次。</p>
     */
    private final List<LockOutlineFillConsumer> pendingFill = new ArrayList<>(8);

    /** 诊断统计（每帧在 {@link #endFillBatch()} 结算一次；只有"按面镂空"真的动手了才计数）。 */
    private int statFaces;
    private int statDroppedFaces;
    private int statSubQuads;
    private static long nextCutoutLogAtMs;
    private MultiBufferSource.BufferSource mainBufferSource;
    private Entity currentEntity;
    private ResourceLocation currentTexture;
    private float[] currentOutline;

    private LockOutlineBufferSource() {}

    /** 获取/复用实例并绑定主缓冲。 */
    public static LockOutlineBufferSource get(MultiBufferSource.BufferSource main) {
        if (INSTANCE == null) INSTANCE = new LockOutlineBufferSource();
        INSTANCE.mainBufferSource = main;
        return INSTANCE;
    }

    /** mixin 在实体渲染前调用：记录当前实体、其主纹理与描边参数（供 fill 双写）。 */
    public static void setCurrent(Entity entity) {
        if (INSTANCE != null) {
            INSTANCE.currentEntity = entity;
            INSTANCE.currentTexture = textureOf(entity);
            INSTANCE.currentOutline = entity != null ? OutlineRegistry.getOutlineColor(entity) : null;
            if (INSTANCE.currentOutline != null) hasAnyOutline = true;
        }
    }

    /** 消费本帧是否有描边实体（AFTER_ENTITIES 后处理判断用；读后清标记）。 */
    public static boolean consumeHasOutline() {
        boolean b = hasAnyOutline;
        hasAnyOutline = false;
        return b;
    }

    public static void clearCurrent() {
        if (INSTANCE != null) INSTANCE.currentEntity = null;
    }

    /** 当前渲染的实体是否需要描边（mixin @ModifyArg 单参数 handler 判断用）。 */
    public static boolean isCurrentOutlined() {
        return INSTANCE != null && INSTANCE.currentEntity != null && INSTANCE.currentOutline != null;
    }

    /** flush 填充缓冲到 FBO（fill RenderType 的 FILL_TARGET 绑 FBO）。 */
    public void endFillBatch() {
        // 先把每层可能留着的"零头"写回它自己那个批次（合法几何永远没有零头，见 pendingFill 注释），
        // 这一步必须在 fillBufferSource.endBatch() 之前 —— 之后 BufferBuilder 已 reset，再写就是脏数据。
        flushPendingFill();
        logCutoutStats();
        fillBufferSource.endBatch();
    }

    /**
     * 写回"攒了但不满 4 个顶点"的零头。
     *
     * <p>时机有两处，都是"上一次写入与这一次写入之间会插进一次 draw"的边界：
     * ① {@code getBuffer} 里换 fill RenderType 之前（{@code BufferSource.getBuffer} 发现 RenderType
     * 变了会先 {@code endBatch} 把上一层画掉，见 {@code MultiBufferSource.java:42-52}）；
     * ② {@link #endFillBatch()} 里刷帧之前。</p>
     *
     * <p>{@code building()} 为假表示这批已经画完/还没开始，此时零头无处可去（只能丢）——
     * 它本来也只可能来自"非 4 倍数的异常几何"，丢了不会比现在更糟。</p>
     */
    private void flushPendingFill() {
        if (pendingFill.isEmpty()) return;
        boolean building = fillBuilder.building();
        for (int i = 0; i < pendingFill.size(); i++) {
            LockOutlineFillConsumer c = pendingFill.get(i);
            statFaces += c.statFaces();
            statDroppedFaces += c.statDroppedFaces();
            statSubQuads += c.statSubQuads();
            if (building) c.flushResidue();
        }
        pendingFill.clear();
    }

    /**
     * 「按贴图 alpha 镂空」的现场证据（5 秒最多一条，只有真的丢过面/切过子面才打）。
     *
     * <p>这条日志是给用户复验用的：看到"丢弃整面 N / 细分 M"就说明 CPU 侧镂空确实在跑；
     * 数字为 0 而描边依旧是整片矩形时，问题就不在 CPU 判据上（去查
     * {@code /yiz fxtoggle probe} 的 ★后处理涂色占比）。</p>
     */
    private void logCutoutStats() {
        if (statFaces == 0) return;
        int faces = statFaces;
        int dropped = statDroppedFaces;
        int sub = statSubQuads;
        statFaces = statDroppedFaces = statSubQuads = 0;
        if (dropped == 0 && sub == 0) return;
        long now = net.minecraft.Util.getMillis();
        if (now < nextCutoutLogAtMs) return;
        nextCutoutLogAtMs = now + 5000L;
        LOGGER.info("[LockOutline] 按贴图 alpha 镂空（CPU 侧，逐层贴图={}）：统计窗口内面={} 丢弃整面={} 切成子面={} "
                        + "—— 丢弃 = 该面 UV 覆盖的纹素全透明（无纹理部位不再整体描边）；"
                        + "子面 = 该面按不透明纹素连通段切开（细丝图案）",
                LockOutlineLayerTexture.strategy(), faces, dropped, sub);
    }

    @Override
    public VertexConsumer getBuffer(RenderType rt) {
        VertexConsumer normal = mainBufferSource.getBuffer(rt);
        boolean outlining = currentEntity != null && currentTexture != null && currentOutline != null;
        // ── 门禁 1（与 vanilla {@code OutlineBufferSource.getBuffer} 一字不差，{@code OutlineBufferSource.java:24-39}）──
        // 只有 {@code outlineProperty == AFFECTS_OUTLINE} 的 RenderType 才参与描边填充：vanilla 的判据就是
        // {@code rt.outline().isPresent()}（{@code RenderType.java:459} + {@code :495}，NONE 的返回 empty；
        // 1.20.1 的 {@code createCompositeState(false)} 就是 NONE，见 {@code RenderType.java:646-648}）。
        // 漏掉这道门禁会出两类错几何：① 影子（{@code entity_shadow} = createCompositeState(false) → NONE）
        // 是<b>贴地</b>的一整片四边形，被填进 FBO 后描边会沿着"地上的影子方块"发光；
        // ② 眼睛/贴花/充能漩涡（eyes / entity_decal / energy_swirl，同样 NONE）等非体表层。
        // 2026-10-04 现场日志已实证这道门禁在生效：影子与 {@code lightning_surface} 都带
        // {@code outlineProperty=none}，都在下面那条 "不参与描边填充" 日志里被挡下。
        if (outlining && rt != null && rt.outline().isPresent()) {
            // ── 门禁 2：影子 pass（与"模型几何镜像"用同一份判据，只抄/只填实体本体）──
            // 影子在 vanilla 里是 NONE 层（门禁 1 已能挡住），这里再按<b>身份</b>挡一次纯粹是兜底：
            // 一旦哪天某个 MOD 用别的影子贴图新建了同族 RenderType（贴图是 memoize 的 key，换了贴图就是
            // 另一个实例），名字仍然固定是 entity_shadow。判据与理由见 ModelMirrorBufferSource#isShadowPass。
            if (ModelMirrorBufferSource.isShadowPass(rt)) {
                if (MirrorDiag.first("lock:skipShadow")) {
                    LOGGER.info("[LockOutline] 排除影子 pass（不填进描边 FBO）: entity={} renderType={} —— "
                                    + "影子只是投影不是体表几何，且是一整片贴地四边形",
                            MirrorDiag.entity(currentEntity), rt);
                }
                return normal;
            }
            // ── 这里<b>故意不</b>套用镜像层的 {@code MirrorExclusion}（手持物/盔甲）──
            // 2026-10-04 核对结论（写下来免得后人再纠结一遍）：
            //   ① 影子：门禁 1 已经挡住（vanilla 的 entity_shadow = createCompositeState(false) → NONE），
            //      现场日志里它的 outlineProperty=none 白纸黑字，所以<b>不需要</b>等价的排除；
            //   ② 地面/水/地形：根本进不来 —— 本类只会被 {@code EntityRenderDispatcherOutlineMixin} 塞给
            //      {@code EntityRenderer.render}，地形/水走的是 chunk 层，永远拿不到这个包装；
            //   ③ 物品/盔甲：确实拿得到（它们与本体共用 affects_outline 的 RenderType 名字），
            //      但那是**目标自身的外观**（锁定高亮把武器/盔甲一起点亮才是预期观感），
            //      排除它只会让高亮缺一块。镜像层排除它们是因为用户明确要求"物品不参与灼烧"，
            //      与描边无关。若哪天用户要求"描边也不含装备"，在这里加
            //      {@code if (MirrorExclusion.isExcluded()) return normal;} 即可（一个门禁的位置）。
            // 一次渲染双写：主缓冲正常 + fill 到 FBO（穿墙，顶点色覆盖为实体描边色）
            //
            // ── 贴图：改成「本层自己的贴图」（逐层解析，纯 CPU）──
            // 一个实体一次渲染会走好几层 RenderType，每层的 UV 属于各自那张贴图。以前所有层都用渲染器主贴图：
            // ① 采别人那层的 UV 会落在基础贴图的不透明区 ⇒ 按贴图 alpha 的判据整片失效；
            // ② 填充 RenderType 自己带的那张贴图就是 fsh 里 Sampler0 采到的那张
            //    （`RenderType.setupRenderState()` 的 TextureStateShard 会 `setShaderTexture(0, tex)`，
            //     紧接着 `BufferUploader._drawWithShader` → `VertexBuffer._drawWithShader` 用
            //     `RenderSystem.getShaderTexture(0)` 绑定 Sampler0，见 VertexBuffer.java:123-127），
            //    所以"给 fill 用该层贴图"同时把 GPU 侧那句 `if (tex.a < 0.1) discard;` 变成了正确的镂空判据。
            //    解析不出就退回主贴图 = 现状（最多观感旧样，绝不会丢描边、更不会黑屏）。
            ResourceLocation layerTexture = LockOutlineLayerTexture.resolve(rt);
            ResourceLocation fillTexture = layerTexture != null ? layerTexture : currentTexture;
            // ── CPU 侧镂空判据（只有"确实解析到本层贴图"时才用：UV 与位图必须同源，否则宁可不镂空）──
            LockOutlineAlphaMask mask = null;
            if (layerTexture != null && rt.mode() == VertexFormat.Mode.QUADS) {
                LockOutlineAlphaMask m = LockOutlineAlphaMask.get(layerTexture);
                if (m.available()) mask = m;
            }
            // 换 fill RenderType 会触发上一层 endBatch→draw ⇒ 先把上一层的"零头"写回它自己的批次
            flushPendingFill();
            LockOutlineFillConsumer fill = new LockOutlineFillConsumer(
                fillBufferSource.getBuffer(LockOutlineRenderType.fill(fillTexture)), currentOutline, mask);
            if (mask != null) pendingFill.add(fill);
            return VertexMultiConsumer.create(fill, normal);
        }
        if (outlining && rt != null && MirrorDiag.first("lock:skipOutlineType:" + MirrorDiag.entity(currentEntity) + ":" + rt)) {
            LOGGER.info("[LockOutline] 该 RenderType 不参与描边填充（非 affects_outline 层，与 vanilla 一致）: "
                            + "entity={} renderType={}",
                    MirrorDiag.entity(currentEntity), rt);
        }
        return normal;
    }

    private static ResourceLocation textureOf(Entity entity) {
        try {
            EntityRenderer er = (EntityRenderer) Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(entity);
            return er != null ? er.getTextureLocation(entity) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    // ══════════════════════ 逐层贴图 + CPU 侧镂空：本轮（第四轮）已实现的位置 ══════════════════════
    //
    // "按贴图 alpha 镂空"现在由三处协作完成，全部是**纯 CPU**、零全局状态：
    //   ① 逐层贴图 {@link LockOutlineLayerTexture#resolve(RenderType)}：本层用哪张贴图
    //      （反射按字段类型找 → {@code CompositeRenderType#toString()} 正则 → 失败回退主贴图）；
    //   ② alpha 位图 {@link LockOutlineAlphaMask#get(ResourceLocation)}：从资源包把这张 PNG 读成
    //      CPU 像素（{@code ResourceManager → Resource#open() → NativeImage.read}），取每纹素 alpha；
    //   ③ 顶点过滤 {@link LockOutlineFillConsumer}：按"每 4 个顶点一个面"攒面后决定
    //      整面丢 / 原样 / 按纹素连通段切子面 —— 写出去的仍然是普通顶点，不碰采样器、不碰混合、不碰 FBO 绑定。
    // 上一轮（第二轮）在这里写过 layerTexture，被"删 blend 节点 + MASK_NO_BLEND"一起拖下水回退；
    // 这次三者都只读 CPU 数据，硬约束（JSON 必须有 opaque=false 的 blend 节点 / 不准绕过 BlendMode 关混合 /
    // 不抢 Sampler0 / 不在 RenderType 里 setSampler）一个都没碰。
}
