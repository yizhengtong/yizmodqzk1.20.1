package net.minecraft.client.yiz.modelmirror;

import com.google.common.collect.ImmutableMap;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultedVertexConsumer;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.entity.Entity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * 单个实体的<b>单个消费者</b>的顶点副本缓冲 —— 一个 {@link MirrorLayer} 对应一个通道。
 *
 * <p>直接继承 vanilla {@link MultiBufferSource.BufferSource} 复用它"按 RenderType 攒批 +
 * {@code endBatch} 刷帧（setupRenderState → draw → clearRenderState）"的机制，
 * 于是状态（深度/混合/剔除/输出目标）全由 RenderType 自己管，不需要手写 GL 状态。</p>
 *
 * <p><b>刷帧完全不碰 delegate</b>：顶点写进的是本通道自己的 {@code builder}
 * （{@code super(new BufferBuilder(...), ImmutableMap.of())}），刷帧走本通道自己的
 * {@code endBatch(本层 RenderType)} —— 既不调用 delegate 的 endBatch，也不要求 delegate 是 BufferSource。</p>
 *
 * <p><b>为什么每个消费者要独立缓冲而不是合批</b>：uniform 逐层不同（火焰要噪声锚点/蔓延进度，
 * 闪电要渐显系数），RenderType 也不同（两个着色器程序），没法合成一次 draw call；
 * 而同一实体同时着火又带电时，两份必须<b>都画</b>，所以是"每实体每层一个通道"，而不是"每实体一个"。</p>
 *
 * <p><b>{@code BufferBuilder} 的两个经典坑（本类全部踩过，注释留档）</b>：</p>
 * <ol>
 *   <li><b>写满不会自动 end</b>：1.20.1 的 {@code BufferBuilder.ensureCapacity} 会自己
 *       {@code MemoryTracker.resize}（每次至少 +2 MiB —— {@code GROWTH_SIZE}，
 *       见 BufferBuilder.java:49-72）。所以"顶点写太多"只会让缓冲变大，
 *       绝不会让 {@code building} 变 false，更不会"中途自动刷帧用错 uniform"。</li>
 *   <li><b>但 {@code end()} 会把缓冲打回未开始状态</b>：{@code end()}/{@code endOrDiscardIfEmpty()}
 *       最后都走 {@code reset()}（BufferBuilder.java:240-248）：{@code building=false} 且
 *       {@code currentElement=null}；此后任何 vertex 调用都会在 {@code currentElement()}
 *       抛 {@code IllegalStateException: BufferBuilder not started}（BufferBuilder.java:358-364）。</li>
 * </ol>
 * <p>而本类的架构是"<b>每帧</b>在 AFTER_ENTITIES flush（= {@code end}）一次"，
 * 所以 {@code getBuffer(...)} 返回的 {@code BufferBuilder} <b>绝不能长期缓存</b>：
 * 跨帧再写就是一个已经 end 掉的缓冲。所有写入统一走 {@link #started()}。</p>
 */
public final class MirrorChannel extends MultiBufferSource.BufferSource {

    private static final Logger LOGGER = LoggerFactory.getLogger("YizModelMirror");

    /**
     * 顶点副本 {@code BufferBuilder} 的初始容量参数。注意 1.20.1 的构造是
     * {@code MemoryTracker.create(capacity * 6)}（BufferBuilder.java:41-43），
     * 而 NEW_ENTITY 顶点恰好 36 = 6 × 6 字节，于是"参数 N ≈ N / 6 个顶点"：
     * 65536 → 393216 字节 ≈ 10900 个顶点，比实测的玩家模型（604 顶点）宽裕一个数量级，
     * 僵尸/大型生物/GeckoLib 模型也不会在一帧里把它写满（写满也只是自动扩容，见类注释）。
     * 每个实体每个消费者一份，实体效果结束后随批次一起释放。
     */
    private static final int BUFFER_BYTES = 65536;

    /** 初始容量对应的顶点数（65536 × 6 ÷ 36）：仅用于"是否走了自动扩容"的限流诊断。 */
    private static final int INITIAL_VERTEX_CAPACITY = BUFFER_BYTES * 6 / 36;

    private final MirrorLayer layer;
    private final Entity entity;

    /**
     * 顶点副本 consumer（懒创建、跨帧复用）。它<b>只持有本通道</b>，不持有 builder
     * （builder 每次 flush 后都会变成"未开始"状态）。
     */
    private VertexConsumer copy;

    /** 本帧参数；{@code null} = 本帧本层未激活（此时不允许写入顶点）。 */
    private MirrorFrame frame;
    /** 本帧本层是否激活：驱动据此决定要不要刷这个通道。 */
    private boolean active;

    /** 本次批次抄了多少顶点（flush 时读走并清零，用于诊断日志里的"顶点=N"）。 */
    private int vertices;
    /** 单帧顶点峰值：只用于"容量不够 → 走了自动扩容"的限流诊断。 */
    private int peakVertices;
    private boolean dirty;
    /**
     * 当前这批顶点是用哪个 RenderType {@code begin} 的。
     * <b>必须记住它</b>：{@code BufferSource.endBatch(type)} 是靠
     * {@code Objects.equals(lastState, type.asOptional())} 做身份匹配的
     * （MultiBufferSource.java:91-103），用另一个 RenderType 去 end 会<b>静默什么都不做</b>
     * （顶点既不画也不清，builder 一直被当成"在 building"越积越多）。
     */
    private RenderType begunType;
    /** 诊断限流标记（见 {@link #logRebeginOnce()} / {@link #logFirstVertexOnce()}）。 */
    private boolean diagRebeginLogged, diagVertexLogged;

    MirrorChannel(MirrorLayer layer, Entity entity) {
        super(new BufferBuilder(BUFFER_BYTES), ImmutableMap.<RenderType, BufferBuilder>of());
        this.layer = layer;
        this.entity = entity;
    }

    // ────────────────────────── 帧生命周期 ──────────────────────────

    public MirrorLayer layer() {
        return layer;
    }

    /** 本帧参数（{@link MirrorLayer#draw} 里取实体、渲染位置、朝向、透明度）。 */
    public MirrorFrame frame() {
        return frame;
    }

    /** 本帧本层是否激活。 */
    public boolean active() {
        return active;
    }

    /** 本帧是否真的抄到了顶点（false = 没有几何可画，驱动会跳过）。 */
    public boolean dirty() {
        return dirty;
    }

    /** 实体渲染前由 {@link ModelMirrorBufferSource.Batch} 调用：激活并记录本帧参数。 */
    void activate(MirrorFrame frame) {
        this.frame = frame;
        this.active = true;
    }

    /**
     * 本帧本层不再生效（状态结束 / 实体已移除）：标记不激活，并丢掉还没刷出去的顶点。
     *
     * <p>为什么要丢：这些顶点是用上一帧的 uniform（噪声锚点/透明度）抄的，带着过期参数画出去
     * 会在原地留下一团错位的火/电。丢掉它们只是这一帧不画，下一帧重新 {@code begin} 即可。</p>
     */
    void deactivate() {
        this.active = false;
        this.frame = null;
        if (dirty || vertices > 0) {
            if (MirrorDiag.first("mm:lost:" + layer.id() + ":" + MirrorDiag.entity(entity))) {
                LOGGER.warn("[ModelMirror] 上一批顶点没有刷帧就被停用: layer={} entity={} 顶点={}（丢弃，避免用过期 uniform 画出去）",
                        layer.id(), MirrorDiag.entity(entity), vertices);
            }
            discard();
        }
    }

    /**
     * <b>唯一的写入前置条件</b>：返回一个保证处于 started（{@code building == true}）状态的缓冲。
     * 所有顶点写入（{@link MirrorCopyConsumer#vertex} / {@link MirrorCopyConsumer#endVertex}）都必须
     * 先过这里 —— 这是"上一帧 flush 之后必须重新 begin"这条不变量的落点。
     *
     * <p><b>为什么用父类 {@code getBuffer} 而不是直接 {@code builder.begin(...)}</b>：
     * 父类 {@code BufferSource} 用 {@code startedBuffers} + {@code lastState} 两本账记录
     * "哪个 builder 正在 building"，{@link #flush()} 里的 {@code endBatch(type)} 只有在这两本账
     * 都对得上时才会真的 {@code RenderType.end(...)} → 把顶点画出去（MultiBufferSource.java:39-103）。
     * 只调 {@code begin} 而不补账本，几何会被静默丢弃（"效果看不见"而不是崩溃，更难查）。
     * {@code getBuffer} 自己会 begin + 记账，所以优先走它。</p>
     *
     * <p>随后仍要复查一次 {@code building()}：万一账本说"已登记"而 builder 实际没在 building
     * （例如 {@link #discard()} 之后通道被复用），继续写就又是
     * {@code BufferBuilder not started} —— 这一道兜底 begin 让本方法无论如何都满足自己的契约。</p>
     *
     * @return 可写的 {@code BufferBuilder}；仅当本层 RenderType 意外为 null（资源重载竞态）时返回
     *         {@code null}（调用方跳过这个顶点，绝不向未 begin 的缓冲写入）
     */
    private BufferBuilder started() {
        if (builder.building()) return builder;

        RenderType type = layer.renderType();
        if (type == null) {
            // 理论不可达：写入只可能发生在 getBuffer 之后，而 getBuffer 在本层 RenderType 为 null 时
            // 根本不返回双写 consumer。真出现（重载竞态）就丢这个顶点，不抛异常、不写脏缓冲。
            if (MirrorDiag.first("mm:noType:" + layer.id() + ":" + MirrorDiag.entity(entity))) {
                LOGGER.warn("[ModelMirror] RenderType 在写入途中为 null（资源重载竞态）: layer={} entity={} → 本顶点丢弃",
                        layer.id(), MirrorDiag.entity(entity));
            }
            return null;
        }

        // 走到这里说明 builder 处于"已 end"状态（正常 = 上一帧 flush；异常 = 被外部 end 掉）。
        // 若此时还留着未刷的顶点，说明那批几何已经在别处被 end 掉了（拿不回来），只能记账并清零，
        // 免得后面的 flush 把新旧顶点混在一起当一批画。
        if (dirty || vertices > 0) {
            if (MirrorDiag.first("mm:lostVerts:" + layer.id() + ":" + MirrorDiag.entity(entity))) {
                LOGGER.warn("[ModelMirror] 上一批顶点在 flush 之前就被 end 掉了: layer={} entity={} 顶点={}"
                                + "（这批几何丢失；接下来会作为新的一批重新 begin）",
                        layer.id(), MirrorDiag.entity(entity), vertices);
            }
            dirty = false;
            vertices = 0;
        }

        // ① 复用父类逻辑：begin + 把 builder 登记进 startedBuffers + lastState = 本类型
        getBuffer(type);
        // ② 兜底：账本与实际状态不一致时补一次 begin（正常路径下 ① 已经 begin 过，这里不会执行）
        if (!builder.building()) {
            startedBuffers.add(builder);
            lastState = Optional.of(type);
            builder.begin(type.mode(), type.format());
        }
        begunType = type;

        // ── 临时诊断：这条日志一出现，就说明"上一帧 flush 把缓冲 end 了 → 本帧重新 begin"的修复路径
        //    真的被走到了（修复前这里就是崩溃点）。每实体每层只打一次，不刷屏。 ──
        if (logRebeginOnce()) {
            LOGGER.info("[ModelMirror] 顶点缓冲重新 begin: layer={} entity={}（上一次 flush 已 end() 掉 builder，"
                            + "写入前必须重新 begin —— BufferBuilder 一旦 end() 就会回到 \"not started\"）",
                    layer.id(), MirrorDiag.entity(entity));
        }
        return builder;
    }

    /**
     * 诊断限流（一）："重新 begin"这条日志只放行一次。
     *
     * <p><b>为什么用自己的布尔标记而不是 {@code MirrorDiag.first(拼字符串)}</b>：{@link #started()} 与
     * {@link MirrorCopyConsumer#endVertex()} 都在"每个顶点"的热路径上，拼 key 会顺带调
     * {@code entity.getType().toShortString()} 与字符串相加 —— 一个玩家模型 600 个顶点就是 600 次无谓分配。</p>
     */
    private boolean logRebeginOnce() {
        if (diagRebeginLogged) return false;
        diagRebeginLogged = true;
        return MirrorDiag.ENABLED;
    }

    /** 诊断限流（二）："首个顶点进入本层缓冲"只放行一次（同样在顶点热路径上）。 */
    boolean logFirstVertexOnce() {
        if (diagVertexLogged) return false;
        diagVertexLogged = true;
        return MirrorDiag.ENABLED;
    }

    /** 顶点副本 consumer（懒创建、跨帧复用；顶点色每帧按 {@link MirrorFrame#alpha()} 刷新）。 */
    VertexConsumer copy() {
        if (copy == null) {
            // 这里刻意只传通道、不传 getBuffer(...) 的返回值：那个 builder 会在本帧 flush 时被
            // end() 掉，缓存它就等于把"已结束的缓冲"留到下一帧用（曾经的崩溃根因）。
            copy = new MirrorCopyConsumer(this);
        }
        return copy;
    }

    /**
     * 刷帧：本通道有几何时走父类的 RenderType.end（状态由 RenderType 管）。
     *
     * @return 本次真正画出去的顶点数（读数后清零，供诊断日志用；0 = 本帧没抄到几何）
     */
    public int flush() {
        int flushed = vertices;
        vertices = 0;
        if (!dirty) return flushed;
        dirty = false;

        // ── 容量诊断（限流，每实体每层一条）：顶点数超过初始容量 ⇒ BufferBuilder 走了自动扩容。
        //    注意 1.20.1 的扩容是"再要 2 MiB"（roundUp → GROWTH_SIZE），不会自动 end，
        //    所以这条日志只说明"该调大初始容量/模型很大"，不是错误。 ──
        if (flushed > peakVertices) {
            peakVertices = flushed;
            if (peakVertices > INITIAL_VERTEX_CAPACITY
                    && MirrorDiag.first("mm:grow:" + layer.id() + ":" + MirrorDiag.entity(entity))) {
                LOGGER.info("[ModelMirror] 顶点缓冲容量不足 → BufferBuilder 自动扩容: layer={} entity={} 顶点={} "
                                + "初始容量≈{} 顶点（1.20.1 写满会自己 resize，不会自动 end；"
                                + "扩容后 builder 跨帧复用，不再有拷贝开销）",
                        layer.id(), MirrorDiag.entity(entity), peakVertices, INITIAL_VERTEX_CAPACITY);
            }
        }

        // 万一顶点已被外部 end 掉（理论不可达，见 started()）：宁可这一帧不画，也不要在未开始的
        // 缓冲上调用 endBatch —— 记一条日志，别静默。
        if (!builder.building()) {
            if (MirrorDiag.first("mm:flushEnded:" + layer.id() + ":" + MirrorDiag.entity(entity))) {
                LOGGER.warn("[ModelMirror] flush 时顶点缓冲已不在 building 状态: layer={} entity={} 顶点={}"
                                + "（本帧无法绘制，顶点已丢弃）", layer.id(), MirrorDiag.entity(entity), flushed);
            }
            return flushed;
        }

        // 用 begin 时的那个 RenderType 收尾：endBatch(type) 走 lastState 身份匹配，
        // 换成别的 RenderType 会静默不画（见 begunType 的注释）。
        RenderType type = begunType != null ? begunType : layer.renderType();
        if (type == null) return flushed;
        endBatch(type);
        return flushed;
    }

    /** 丢弃未刷的顶点（状态已结束 / 实体已移除）：不能带着过期 uniform 画出去。 */
    public void discard() {
        dirty = false;
        vertices = 0;
        // 手动 end 就要手动清父类的两本账：否则 startedBuffers 里会留下一个"其实没在 building"的
        // builder，下次 getBuffer 会因为"已登记"而跳过 begin → 又是 BufferBuilder not started。
        startedBuffers.remove(builder);
        lastState = Optional.empty();
        if (builder.building()) builder.end().release();
        begunType = null;
    }

    /**
     * 覆盖顶点色的副本 consumer（复刻 vanilla {@code EntityOutlineGenerator} 的思路）：
     * 原样保留 NEW_ENTITY 的全部属性（UV0 / UV1 overlay / UV2 光照 / 法线），只把颜色换成
     * "白 + 本帧 alpha" —— 直接用实体顶点色会把受击红闪、隐身 0.15 之类的染色带进特效里。
     *
     * <p><b>本类刻意不缓存目标 {@code BufferBuilder}</b>：那个 builder 每帧都会被
     * {@link #flush()} {@code end()} 一次（{@code RenderType.end → builder.end() → reset()}），
     * 缓存它的引用就等于把"已结束的缓冲"留到下一帧用 —— 那正是
     * {@code BufferBuilder not started} 崩溃的根因。每次写入都通过 {@link #started()} 现取。</p>
     */
    private static final class MirrorCopyConsumer extends DefaultedVertexConsumer {

        private final MirrorChannel channel;

        private double x, y, z;
        private float u, v;
        private int overlayU, overlayV, lightU, lightV;
        private float nx, ny, nz;
        /**
         * 顶点色 alpha（0~255）。刻意不放在 {@link DefaultedVertexConsumer} 的 defaultA 里：
         * {@code VertexMultiConsumer} 会把 {@code defaultColor(...)} 转发给每个下游 consumer，
         * 放在默认色里就有被外部覆盖的风险（效果会在淡出时突然变全亮）。
         * 也不做成字段：每帧从 {@link MirrorChannel#frame()} 现取，天然跟帧同步。
         */
        private int alpha = 255;

        MirrorCopyConsumer(MirrorChannel channel) {
            this.channel = channel;
        }

        @Override
        public VertexConsumer vertex(double x, double y, double z) {
            // 顶点链的第一步就把缓冲准备好：任何一次写顶点之前，builder 都必须是 started 的
            // （上一帧的 flush 已经 end 过它了，见 MirrorChannel#started）。
            channel.started();
            this.x = x; this.y = y; this.z = z;
            return this;
        }

        @Override
        public VertexConsumer color(int r, int g, int b, int a) {
            return this;   // 忽略原色，用"白 + 本帧 alpha"
        }

        @Override
        public VertexConsumer uv(float u, float v) {
            this.u = u; this.v = v;
            return this;
        }

        @Override
        public VertexConsumer overlayCoords(int u, int v) {
            this.overlayU = u; this.overlayV = v;
            return this;
        }

        @Override
        public VertexConsumer uv2(int u, int v) {
            this.lightU = u; this.lightV = v;
            return this;
        }

        @Override
        public VertexConsumer normal(float x, float y, float z) {
            this.nx = x; this.ny = y; this.nz = z;
            return this;
        }

        @Override
        public void endVertex() {
            MirrorFrame f = channel.frame();
            if (f != null) {
                alpha = Math.max(0, Math.min(255, (int) (f.alpha() * 255.0f)));
            }
            // ── 临时诊断：双写通道真的收到顶点了吗（每实体每层只打第一条，不拼字符串）──
            if (channel.logFirstVertexOnce()) {
                LOGGER.info("[ModelMirror] 开始抄写顶点: layer={} entity={}（模型顶点已进入本层缓冲）",
                        channel.layer.id(), MirrorDiag.entity(channel.entity));
            }
            // 再保证一次（幂等：已 started 时只是一次 building() 判断）—— 万一模型/上层 consumer 没走
            // 上面的 vertex(double,double,double) 而直接调 14 参 vertex（ModelPart 就是这条快路径，
            // 但它的默认实现会拆成逐个 setter，见 VertexConsumer#vertex(14 参)），这里同样不会写到未开始的缓冲上。
            BufferBuilder target = channel.started();
            if (target == null) return;   // 仅 RenderType 意外为 null 时发生（见 started()）：丢弃该顶点
            target.vertex(x, y, z)
                    .color(255, 255, 255, alpha)
                    .uv(u, v).overlayCoords(overlayU, overlayV).uv2(lightU, lightV).normal(nx, ny, nz)
                    .endVertex();
            channel.dirty = true;
            channel.vertices++;
        }
    }
}
