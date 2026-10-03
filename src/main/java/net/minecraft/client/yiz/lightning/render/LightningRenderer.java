package net.minecraft.client.yiz.lightning.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.yiz.lightning.fx.ArcEffect;
import net.minecraft.client.yiz.lightning.fx.ShockArcs;
import net.minecraft.client.yiz.lightning.geometry.ArcGeometry;
import net.minecraft.client.yiz.lightning.orchestrate.LightningEmitter;
import net.minecraft.client.yiz.lightning.util.ShaderTimeUtil;
import net.minecraft.client.yiz.modelmirror.RenderStateSnapshot;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 闪电特效渲染器 —— <b>一个 pass 画两条队列</b>：编号 1「闪电链」与编号 4「围弧」。
 *
 * <ul>
 *   <li>{@code ARCS}（编号 1）：两点之间的链式电弧（{@code LightningFX.spawnArc} 两个重载，
 *       端点可由 {@code PositionSupplier} 跟随实体）—— 世界空间里的折线，
 *       换算成相机相对坐标后走 {@code rendertype_lightning_arc}；</li>
 *   <li>{@code ShockArcs}（编号 4）：感电实体体外的短弧群（{@link ShockArcs}）。
 *       它的弧度几何同样是<b>独立的三维折线</b>，但端点取自"模型自己写出的顶点"
 *       （{@code ShockAnchors} 采的锚点），于是"空间电弧"的形态与"模型在哪弧就在哪"的位置同时成立。
 *       顶点空间 = 模型顶点所在的空间（视图空间）⇒ 写入时用单位阵；
 *       两条队列共用 {@link ArcGeometry}、{@code rendertype_lightning_arc}、
 *       同一个 BufferBuilder 与同一套渲染状态（唯一的差别就是"要不要乘视图矩阵"，
 *       因为编号 1 的端点是相机相对世界坐标、编号 4 的端点是模型顶点，见 {@code ShockArcs#IDENTITY}）。</li>
 * </ul>
 *
 * <h2>⚑ 第六轮：编号 4 不再走"模型几何镜像"，回到本 pass</h2>
 * <p>第四/五轮的编号 4 是<b>模型几何镜像</b>的一个消费者（{@code ShockSurfaceLayer} +
 * {@code rendertype_shock_surface}，几何 = 模型顶点本身）。用户实测否掉了它：
 * <b>"效果不好，现在是幽蓝色是纹理在表面"</b>。根因是结构性的 ——
 * 只要几何是"模型表面顶点"，"哪个像素发光"就由"哪块皮肤可见"决定，
 * 无论片元里怎么往外积分类都是"贴在皮肤上的一层图案"。
 * 第五轮试图在片元里沿视线往外取 0.02~0.35 格的样来救，仍然只是让那层图案往外挪了一点，
 * 观感没变（且新增了三个着色器文件与一条镜像层）。</p>
 * <p>第六轮改成"<b>独立的弧几何 + 锚点来自模型顶点</b>"：几何重新是有自己顶点的三维折线
 * （飘在体外，不再由皮肤像素决定），端点则挂在 {@code ShockAnchors} 从模型顶点里采出的锚点上
 * ⇒ 位置仍然天然跟随模型。<b>那一层与它的三个着色器资源已整体删除</b>（见 {@code ModelMirrorLayers}）。</p>
 *
 * <h2>⚑ 黑屏档案（为什么闪电类"贴模型"层曾经全灭，别再加回来）</h2>
 * <p>模型几何镜像管线里<b>曾经</b>有两层闪电：{@code LIGHTNING}（原编号 2，{@code rendertype_lightning_surface}）
 * 与 {@code AIR_ARC}（原编号 3，{@code rendertype_lightning_modelarc}）。用户实测：<b>两层都是一开
 * （有实体感电时）就整屏发黑</b>，一模一样；而同一管线里的火焰层一直正常 ⇒ 管线本体没问题，
 * 问题在"闪电类层"。逐行核对后收敛到两条共因：</p>
 * <ol>
 *   <li><b>它们的着色器 JSON 缺 {@code "blend"} 节点</b>（全工程仅此二例；对照
 *       {@code rendertype_flame_surface.json} 有）。1.20.1 的 {@code ShaderInstance.apply()} 每次 draw 都
 *       {@code this.blend.apply()}：缺节点 ⇒ 默认 {@code new BlendMode()} ⇒ {@code opaque = true}
 *       ⇒ {@code RenderSystem.disableBlend()} —— RenderType 的加法混合刚打开就被着色器程序自己关掉，
 *       而两层的片元输出是"RGB 已乘过强度"的暗值 ⇒ 覆盖写下去就是近黑。</li>
 *   <li>两层都用 {@code RenderSystem.setShaderColor(fade,…)} 当强度通道包住 {@code channel.flush()}
 *       —— 全工程唯一在"每帧每实体"热路径上改全局 {@code ColorModulator} 的写法。</li>
 * </ol>
 * <p>这两层、它们的状态表/生成入口/着色器（含原先"保留但不注册"的
 * {@code rendertype_lightning_modelarc.*}）<b>全部删除，不留档</b>。</p>
 * <p>第四轮曾用"照抄火焰层那条安全路径"（JSON 有 {@code "blend"} 节点、强度走顶点色 alpha、
 * RenderType 与火焰层同构、不绑纹理）把编号 4 接回镜像管线 —— 那条路<b>确实没黑屏</b>，
 * 但观感被用户否掉（见上）。第六轮的做法更彻底：<b>根本不再让任何闪电类着色器走镜像管线</b>，
 * 编号 4 回到本 pass 里那条从编号 1 起就一直在跑的路径（同一个着色器、同一种混合、同一套状态）。</p>
 * <p>结论留给下一轮：<b>要恢复"贴模型闪电"，先解决"几何来自皮肤顶点 ⇒ 观感必然是表面图案"
 * 这个结构问题（本轮的答案是把几何换成独立折线 + 锚点取顶点），而不是继续调片元。</b></p>
 */
public final class LightningRenderer {

    private static final List<ArcEffect> ARCS = new CopyOnWriteArrayList<>();
    private static final List<LightningEmitter> EMITTERS = new CopyOnWriteArrayList<>();

    private LightningRenderer() {}

    public static void enqueue(ArcEffect e) { ARCS.add(e); }
    public static void enqueueEmitter(LightningEmitter e) { EMITTERS.add(e); }

    public static void clear() { ARCS.clear(); EMITTERS.clear(); }

    /**
     * 只丢掉"两实体之间的链式电弧"（{@link #ARCS}）。
     *
     * <p><b>开关 1</b>（{@code /yiz fxtoggle 1} 关掉时调用）：那些弧是用关之前的参数生成的，
     * 留着就是半成品残影。围弧（开关 4）<b>不在这个列表里</b>（它在 {@link ShockArcs} 自己的
     * 状态表里），所以关掉 1 不会顺手关掉 4 —— 两个开关互不影响。</p>
     */
    public static void clearArcs() { ARCS.clear(); }

    public static void tick() {
        float dt = 0.05f;
        for (ArcEffect a : ARCS) a.life -= dt;
        ARCS.removeIf(a -> a.life <= 0f);
        List<LightningEmitter> dead = new java.util.ArrayList<>();
        for (LightningEmitter e : EMITTERS) if (!e.tick(dt)) dead.add(e);
        EMITTERS.removeAll(dead);
    }

    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        try {
            // 现在有两条队列共用本 pass：ARCS（开关 1 链式电弧）与 ShockArcs（开关 4 围弧）。
            // 两者都空时不进（省掉一次全局状态快照），但只要有一条要画就进。
            if (ARCS.isEmpty() && !ShockArcs.hasLiveArcs()) return;
            if (LightningShaders.arc == null) return;

            // ── 状态快照 → try/finally 全量还原 ──
            // 本 pass 用 {@code Tesselator} 立即绘制，并要开混合（加法）/关深度掩码/关切除。
            // 只要在恢复之前抛异常（着色器重载竞态、几何 NaN、第三方 mod 干扰…），
            // 「additive 混合 + depthMask(false) + 不剔除」就会留在全局：之后的手/云/雨/粒子/GUI 全部叠加错乱，
            // 而且它不会自愈（下一帧这个 pass 先把状态又设一遍，漏的还是漏的）。
            // 所以这里快照整份、finally 整份还回（逐项理由见 RenderStateSnapshot）。异常本身不吞、照常往上抛。
            RenderStateSnapshot snapshot = RenderStateSnapshot.capture();
            try {
                renderAll(event);
            } finally {
                snapshot.restore("lightning-arc");
            }
        } finally {
            // ── 围弧锚点的"本帧被渲染过"标记必须每帧无条件清 ──
            // 实体渲染发生在 AFTER_PARTICLES 之前，所以"这一帧有没有重新采过锚点"完全由这个标记回答：
            // 提前 return（没东西可画 / 着色器没就绪）也必须清，否则实体被视锥剔除时
            // 会拿着上一帧的锚点继续画（用户明确要求：拿不到锚点就不画，绝不退回包围盒估位置）。
            ShockArcs.endFrame();
        }
    }

    /** 实际绘制（全局状态由调用方用 {@link RenderStateSnapshot} 包住）。 */
    private static void renderAll(RenderLevelStageEvent event) {
        Vec3 cam = event.getCamera().getPosition();

        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();

        ShaderTimeUtil.setITime(LightningShaders.arc);
        RenderSystem.setShader(() -> LightningShaders.arc);
        Matrix4f mat = event.getPoseStack().last().pose();
        float pt = event.getPartialTick();
        BufferBuilder bb = Tesselator.getInstance().getBuilder();
        bb.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        boolean wrote = false;

        // ── 链式电弧（开关 1）───────────────────────────────────────────────
        // 渲染段兜底闸门：生成入口已经挡过（spawnArc 返回 null + 切换时 clearArcs），
        // 这里再挡一道，保证"即便有人绕过生成入口直接 enqueue 也画不出来"。
        if (net.minecraft.client.yiz.tool.FxDebugSwitches.chainArc()) {
            for (ArcEffect a : ARCS) {
                float fade = net.minecraft.util.Mth.clamp(a.life / a.maxLife, 0f, 1f);
                Vec3 from = a.fromSup != null ? a.fromSup.get(pt) : a.from;
                Vec3 to = a.toSup != null ? a.toSup.get(pt) : a.to;
                ArcGeometry.emitArc(bb, mat, from.subtract(cam), to.subtract(cam),
                        a.seed, a.width, a.r, a.g, a.b, fade);
                wrote = true;
            }
        }

        // ── 围弧（开关 4，编号 4）───────────────────────────────────────────
        // 独立的三维短弧几何（有自己的顶点、飘在身体外的空间里），端点取自模型自己写出的顶点
        // ⇒ "空间里的电弧"的形态 + "模型被搬到哪弧就在哪"的位置。
        // ⚑ 坐标空间：围弧的顶点<b>已经是视图空间</b>（锚点就是模型顶点，视图旋转已烤进去），
        //    所以它<b>不能</b>再乘上面那个 mat（那会把视图旋转乘两遍）；单位阵在 ShockArcs 内部，
        //    理由与取证写在它的 IDENTITY 字段注释里。写进同一个 BufferBuilder、共用同一个着色器
        //    与同一套状态 —— 顶点空间与实体本体/贴模型火焰一致，渲染路径与编号 1 一致。
        // 本段自带开关闸门（与开关 1 互不影响：关掉 1 不会关掉 4，反之亦然）。
        if (net.minecraft.client.yiz.tool.FxDebugSwitches.surfaceArcs() && ShockArcs.emit(bb)) {
            wrote = true;
        }

        if (wrote) {
            BufferUploader.drawWithShader(bb.end());
        } else {
            bb.end();
        }

        RenderSystem.enableCull();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
    }
}
