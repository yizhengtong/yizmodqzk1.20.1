package net.minecraft.client.yiz.modelmirror;

import net.minecraft.client.yiz.flame.render.FlameSurfaceLayer;
import net.minecraft.client.yiz.lightning.render.LightningAirArcLayer;
import net.minecraft.client.yiz.lightning.render.LightningSurfaceLayer;

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
 */
public final class ModelMirrorLayers {

    /** 贴模型火焰（{@code rendertype_flame_surface}）。 */
    public static final MirrorLayer FLAME = new FlameSurfaceLayer();
    /** 体表闪电（{@code rendertype_lightning_surface}）。 */
    public static final MirrorLayer LIGHTNING = new LightningSurfaceLayer();
    /**
     * 围绕实体的游离弧（{@code rendertype_lightning_modelarc}）—— 命中瞬间/技能在目标身上炸开的短寿命电弧。
     *
     * <p><b>它取代了"按包围盒撒弧"的旧通道</b>：{@code LightningRenderer.SURFACE}（{@code SurfaceArcEffect}）
     * 那种方盒子电弧不再挂在实体身上（用户要求"空气电弧也要跟着模型轮廓"），
     * 只剩"球状闪电表面"那一种用法（球不是实体，镜像层无法表达）。</p>
     */
    public static final MirrorLayer AIR_ARC = new LightningAirArcLayer();

    /**
     * 全部消费者，顺序 = 通道下标顺序 = 同实体多层的绘制顺序。
     * 各层写各自的缓冲、画各自的 RenderType，顺序只影响叠加的细微差别（都不写深度），
     * 但固定顺序让日志与观感都可复现。
     */
    public static final List<MirrorLayer> ALL = List.of(FLAME, LIGHTNING, AIR_ARC);

    private ModelMirrorLayers() {}
}
