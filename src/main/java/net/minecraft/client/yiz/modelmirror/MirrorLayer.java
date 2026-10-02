package net.minecraft.client.yiz.modelmirror;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.entity.Entity;

/**
 * 模型几何镜像的一个"消费者"（一层效果）：决定<b>哪些实体要本层、本层的顶点透明度与刷帧 uniform、以及怎么刷帧</b>。
 *
 * <p>存在的意义：贴模型火焰与体表闪电要的是<b>同一份</b>几何 —— 实体自己的模型顶点（经
 * {@link ModelMirrorBufferSource} 双写抄一份），差别只在"用哪个 RenderType、什么参数把它画出来"。
 * 把"画"这一步抽成策略接口，几何镜像机制就只有一份，新增一种体表效果 = 新增一个实现类 +
 * 在 {@link ModelMirrorLayers} 登记一行，不需要再复制一遍"换缓冲源 / 抄顶点 / 每帧重新 begin / 排影子"
 * 这些已经踩过坑的部分。</p>
 *
 * <p><b>顶点透明度不是 uniform</b>：{@link MirrorFrame#alpha()} 写在顶点色里（片元着色器按需读取），
 * 这样"逐实体淡入淡出"不需要额外的 uniform 通道，也不会被
 * {@code VertexBuffer#_drawWithShader} 对 ColorModulator 的覆盖影响（那条路只认 RenderSystem 的着色器颜色）。</p>
 */
public interface MirrorLayer {

    /** 消费者短名（日志与诊断用，必须唯一且在限流 key 里当前缀）。 */
    String id();

    /**
     * 本层本次要用的 {@link RenderType}；<b>尚未解析出来（首帧 / 资源重载中）返回 {@code null}</b>。
     *
     * <p>镜像机制用它做两件事：① 本层能不能参与（null = 本帧不抄）；② 递归保护 —— 自己的 pass 绝不双写自己。</p>
     */
    RenderType renderType();

    /**
     * 实体渲染前调用：判断本帧要不要本层，并给出该帧参数。
     *
     * <p>实现方在这里查自己的状态（着火状态 / 感电强度），把逐实体量算好塞进 {@link MirrorFrame} ——
     * 刷帧时不再重复查询状态，避免"顶点是用 A 帧的参数抄的、却用 B 帧的参数画"。</p>
     *
     * @return 本帧需要本层时返回参数；不需要（状态结束 / 不可见 / 着色器未就绪）返回 {@code null}
     */
    MirrorFrame prepare(Entity entity, double dx, double dy, double dz, float yaw);

    /**
     * 刷帧：绑定本层 uniform（需要时保存/还原被自己改动的全局状态）并调用 {@link MirrorChannel#flush()}。
     *
     * <p>交给层自己而不是驱动统一做，是因为"绑 uniform"与"还原全局状态"必须夹住这一次 draw：
     * 体表闪电要临时改 {@code RenderSystem} 的着色器颜色（ColorModulator 的唯一有效入口，见实现注释），
     * 火焰要绑主目标深度纹理 —— 两者都必须在自己的 draw 之后立刻还原。</p>
     *
     * <p>本层自己的诊断日志（画了多少顶点、什么参数）也写在实现里，驱动不需要知道这些细节。</p>
     */
    void draw(MirrorChannel channel);

    /**
     * 本层刷帧前是否必须把<b>主渲染目标的深度纹理</b>绑到 Sampler0（片元着色器逐样本深度截断用）。
     * 返回 {@code false} 的层，驱动不会替它付这次纹理绑定。
     */
    boolean needsSceneDepth();
}
