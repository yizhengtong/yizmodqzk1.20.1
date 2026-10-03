package net.minecraft.client.yiz.modelmirror;

import net.minecraft.client.yiz.flame.render.FlameSurfaceLayer;

import java.util.List;

/**
 * 模型几何镜像的消费者登记表 —— <b>唯一</b>的接线点。
 *
 * <p>为什么用"一个持有静态常量的类"而不是各消费者自己往某个静态表里注册：Java 的类初始化是惰性的，
 * "谁先被加载"取决于调用顺序，自注册表会出现"某一层这局游戏第一次用到才注册、于是上一帧的
 * 迭代顺序/日志顺序漂移"这类难查的问题。这里在一个类里显式 new 出全部消费者，
 * {@link ModelMirrorBufferSource} 只引用 {@link #ALL}，初始化顺序因此是确定的。</p>
 *
 * <p>新增一种体表效果 = 写一个 {@link MirrorLayer} 实现 + 在这里加一行；镜像机制本身不需要改。</p>
 *
 * <h2>当前只有一层：{@code FLAME}（贴模型着火）</h2>
 * <p>它长期正常工作，也是"管线本体没问题"的活证据：双写（{@link ModelMirrorBufferSource}）、
 * 每帧快照/还原（{@link RenderStateSnapshot}）、AFTER_ENTITIES 刷帧（{@link ModelMirrorRenderer}）
 * 三段都在正常出图。<b>闪电类的东西现在一个都不在本表里</b>（见下）。</p>
 *
 * <h2>⚑ 已移除的层（三轮，症状各不同）</h2>
 * <ol>
 *   <li><b>{@code LIGHTNING}（原编号 2）与 {@code AIR_ARC}（原编号 3）：一开即整屏发黑。</b>
 *       用户逐项实测 9 个开关（2026-10-04）：这两层"一开（有实体感电时）就整屏发黑、一关即恢复"，
 *       而同一管线里的火焰层一直正常 ⇒ 管线本体没问题，问题在"闪电类层"。
 *       逐行核对后收敛到两条共因（完整取证见 {@code LightningRenderer} 的类注释）：
 *       ① 着色器 JSON 缺 {@code "blend"} 节点（⇒ {@code parseBlendNode(null)} ⇒ 默认
 *       {@code new BlendMode()} ⇒ {@code opaque = true} ⇒ {@code BlendMode.apply()} 自己
 *       {@code RenderSystem.disableBlend()}，于是 RenderType 刚打开的加法混合被着色器程序关掉，
 *       而片元输出是"RGB 已乘过强度"的暗值 ⇒ 覆盖写下去就是近黑）；
 *       ② 以 {@code RenderSystem.setShaderColor(fade,…)} 当强度通道包住 {@code channel.flush()}
 *       —— 全工程唯一在"每帧每实体"热路径上改全局 {@code ColorModulator} 的写法。
 *       两层连同状态表、生成入口、着色器资源<b>全部删除，不留档</b>。</li>
 *
 *   <li><b>{@code SHOCK}（编号 4，第四/五轮）：没黑屏，但观感被否。</b>
 *       第四轮按上面两条逐条规避（JSON 有 {@code "blend"} 节点、强度走顶点色 alpha、
 *       RenderType 与火焰层逐项同构、不绑纹理），于是它<b>确实没有黑屏</b>；
 *       但它把"几何 = 实体模型顶点、发光 = 片元着色器在体表算图案"这条路走到了头，
 *       用户实测反馈：<b>"效果不好，现在是幽蓝色是纹理在表面"</b>。</li>
 * </ol>
 *
 * <h2>⚑ 第六轮定案：编号 4 的观感问题是<b>结构</b>问题，只能换几何</h2>
 * <p>只要几何是"模型表面顶点"，"哪些像素能发光"就由"哪些皮肤像素可见"决定
 * ⇒ 无论片元里怎么往外积分类（第五轮甚至沿视线在体外 0.02~0.35 格做了 14 步行进积分），
 * 观感永远是<b>贴在皮肤上的一层图案</b>。所以第六轮把几何<b>整个换掉</b>：</p>
 * <ul>
 *   <li>弧恢复成<b>独立的三维折线几何</b>（有自己的顶点、飘在身体外的空间里，
 *       形态与旧 4 号一致 ⇒ 用户满意的那种"空间电弧"）；</li>
 *   <li>端点取自"<b>模型自己写出的顶点</b>"里采出的一小撮锚点
 *       （{@code ShockAnchors}），于是"模型被渲染到哪里，弧就在哪里"这条不变量<b>仍然成立</b>
 *       —— 不是复算出来的，是同一份顶点流；</li>
 *   <li>渲染回到编号 1 那条已验证的路径（{@code LightningRenderer} 的 pass +
 *       {@code ArcGeometry} 折线 + {@code rendertype_lightning_arc}），
 *       <b>不新增任何着色器、闪电类着色器再也不走镜像管线</b> ⇒ 2/3 号那类黑屏风险从结构上消失。</li>
 * </ul>
 * <p>于是 {@code SHOCK} 层与它的三个着色器资源（{@code rendertype_shock_surface.json/.vsh/.fsh}）
 * <b>本轮整体删除</b>，{@link #ALL} 恢复成只有 {@code FLAME}。
 * 围弧现在的家是 {@code net.minecraft.client.yiz.lightning.fx.ShockArcs}，
 * 它<b>不是</b>镜像层：它只要"模型顶点的最终坐标"，方法是在
 * {@link ModelMirrorBufferSource#getBuffer} 的多路复用链末端挂一个"只看不写"的顶点接收器
 * （见 {@code ShockArcs#beginFrame}），不占 MirrorChannel、不要 RenderType、不画任何顶点。</p>
 *
 * <p><b>留给下一轮</b>：要再往镜像管线里加"闪电类层"，请先回答"它的几何是不是皮肤顶点"——
 * 是的话观感注定是表面图案（本轮已经证过一次）；而"贴模型"这件事本身不需要层，
 * 只需要 {@code ShockArcs} 那种"借顶点当锚点"的下游。</p>
 */
public final class ModelMirrorLayers {

    /** 贴模型火焰（{@code rendertype_flame_surface}）—— "着火"贴实体实际模型。 */
    public static final MirrorLayer FLAME = new FlameSurfaceLayer();

    /**
     * 全部消费者，顺序 = 通道下标顺序 = 同实体多层的绘制顺序。
     * 各层写各自的缓冲、画各自的 RenderType，顺序只影响叠加的细微差别（都不写深度），
     * 但固定顺序让日志与观感都可复现。
     *
     * <p><b>现在只有一层</b>：{@link #FLAME}。曾经的 {@code LIGHTNING}（2）、{@code AIR_ARC}（3）
     * 与 {@code SHOCK}（4）都已移除，理由见类注释 —— 前两个是黑屏，后一个是"几何=皮肤顶点 ⇒
     * 观感必然是表面图案"。编号 4 的围弧现在的实现不在本表里（见类注释末尾）。</p>
     */
    public static final List<MirrorLayer> ALL = List.of(FLAME);

    private ModelMirrorLayers() {}
}
