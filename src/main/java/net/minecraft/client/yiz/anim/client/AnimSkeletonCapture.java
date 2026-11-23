package net.minecraft.client.yiz.anim.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.yiz.mixin.ModelPartAccessor;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector2f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * 编辑器视口渲染玩家时，<b>在几何真正提交给 GPU 的那一刻</b>把每个 {@link ModelPart} 的
 * 完整变换矩阵（= GUI 相机位姿 × 实体渲染内部变换 × 父链骨骼 × 本骨骼 translateAndRotate）
 * 和它的 {@code Cube} 包围盒一起记下来，供 gizmo / 黄色包围盒直接使用。
 *
 * <h2>为什么旧方案会偏</h2>
 * <ol>
 *   <li>旧实现自己在 {@code setupAnim} TAIL 里用 {@code T(pivot)·R·T(-pivot)} 重建矩阵，
 *       多出来的 {@code T(-pivot)} 让矩阵变成"旋转模型空间绝对坐标"，而 {@code Cube} 的顶点本来就是
 *       相对枢轴的局部坐标（原版 {@code ModelPart#translateAndRotate} 只有 {@code T(pivot)·R·S}），
 *       两套坐标约定不一致；</li>
 *   <li>旧实现假设 body 是 head/arm/leg 的父骨骼，但原版 {@code HumanoidModel} 里所有骨骼都是
 *       根节点的<b>平级</b>子节点（{@code headParts()/bodyParts()} 只返回平级列表），
 *       body 的旋转会被错误地传给四肢；</li>
 *   <li>旧实现漏掉了 {@code PlayerRenderer#scale} 的 0.9375 缩放、{@code xScale/yScale/zScale}
 *       持久字段，以及 GUI 投影里 y 轴的方向（{@code cy - v.y*zoom} 把整个模型上下镜像了）；</li>
 *   <li>旧包围盒是手写的绝对坐标常量，和模型真实几何（帽子/袖子/护腿等层、动画缩放）不可能一致。</li>
 * </ol>
 *
 * <h2>现在的约定</h2>
 * 捕获到的矩阵直接是"骨骼局部坐标（格，y 向下，16px=1格）→ GUI 屏幕像素坐标"的<b>同一个</b>矩阵，
 * 屏幕上某个顶点画在哪由 GPU 用的这个矩阵决定，所以 2D 叠加绘制与实体渲染必然重合，
 * 编辑器不需要再推导 rotateY(180)、scale(-1,-1,1)、translate(0,-1.501,0)、zoom/pan 中的任何一步。
 *
 * <p>捕获只发生在 {@link #begin()} / {@link #end()} 之间（编辑器视口渲染那一小段），
 * 其余时间 {@link #onPartRender} 立即返回。数据按 {@link ModelPart} 实例身份索引，
 * 所以护甲层等其它模型实例不会污染玩家模型。</p>
 */
public final class AnimSkeletonCapture {

    /** 一个轴对齐盒（骨骼局部坐标，单位格）。{@code Cube.minX..maxZ} 本身就是 1/16 格单位。 */
    public static final class Box {
        public final float minX;
        public final float minY;
        public final float minZ;
        public final float maxX;
        public final float maxY;
        public final float maxZ;

        Box(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxY = maxY;
            this.maxZ = maxZ;
        }
    }

    /** 一根骨骼的捕获结果：局部→屏幕矩阵 + 该骨骼自己的 cube 盒（不含子骨骼）。 */
    public static final class BoneFrame {
        private final Matrix4f localToScreen;
        private final List<Box> boxes;

        BoneFrame(Matrix4f localToScreen, List<Box> boxes) {
            this.localToScreen = localToScreen;
            this.boxes = boxes;
        }

        public List<Box> boxes() {
            return this.boxes;
        }

        /** 把骨骼局部坐标（格）投影成 GUI 像素坐标；返回向量的 x/y 可直接给 {@code GuiGraphics}。 */
        public Vector3f project(float x, float y, float z) {
            return this.localToScreen.transformPosition(new Vector3f(x, y, z));
        }

        /** 骨骼枢轴（局部原点）在屏幕上的位置。 */
        public Vector3f pivot() {
            return this.project(0F, 0F, 0F);
        }

        /** 骨骼局部第 {@code axis} 根轴（0=x,1=y,2=z）在屏幕上的单位方向（2D）。 */
        public Vector2f screenAxis(int axis) {
            Vector3f origin = this.pivot();
            // 取半格长的向量做差分，避免只用到 3x3 线性部分时被误归一化/丢掉镜像
            Vector3f tip = this.project(axis == 0 ? 0.5F : 0F, axis == 1 ? 0.5F : 0F, axis == 2 ? 0.5F : 0F);
            float dx = tip.x - origin.x;
            float dy = tip.y - origin.y;
            float length = (float) Math.sqrt(dx * dx + dy * dy);
            if (length < 1.0E-4F) {
                // 轴几乎正对镜头：给一个稳定退化方向，拖动仍可用
                return new Vector2f(axis == 0 ? 1F : 0F, axis == 1 ? -1F : 0F);
            }
            return new Vector2f(dx / length, dy / length);
        }

        /** 把局部原点平移到 {@code (dx,dy,dz)}（同一骨骼坐标系内），包围盒不变。 */
        public BoneFrame offset(float dx, float dy, float dz) {
            return new BoneFrame(new Matrix4f(this.localToScreen).translate(dx, dy, dz), this.boxes);
        }

        /** 用一个以局部原点为中心的立方体替换包围盒（用于 right_item 这种没有真实 cube 的点）。 */
        public BoneFrame box(float half) {
            List<Box> single = List.of(new Box(-half, -half, -half, half, half, half));
            return new BoneFrame(new Matrix4f(this.localToScreen), single);
        }
    }

    private static final Map<ModelPart, BoneFrame> FRAMES = new IdentityHashMap<>();
    private static boolean capturing;

    private AnimSkeletonCapture() {}

    /** 编辑器视口开始渲染玩家前调用：清掉上一帧的数据并打开捕获开关。 */
    public static void begin() {
        FRAMES.clear();
        capturing = true;
    }

    /** 视口渲染结束后调用。 */
    public static void end() {
        capturing = false;
    }

    /**
     * 由 {@code ModelPart#render} 的 HEAD 注入调用（此时 pose 里是本骨骼的父链变换，
     * 本骨骼自己的 translateAndRotate 还没执行）。这里按原版完全相同的顺序补上
     * {@code T(x/16,y/16,z/16) · Rz·Ry·Rx · S}，得到 GPU 实际使用的最终矩阵。
     */
    public static void onPartRender(ModelPart part, PoseStack poseStack) {
        if (!capturing || part == null || !part.visible || part.skipDraw) {
            return;
        }
        List<ModelPart.Cube> cubes = ((ModelPartAccessor) (Object) part).yizmodqzk$getCubes();
        if (cubes == null || cubes.isEmpty()) {
            return;
        }
        Matrix4f matrix = new Matrix4f(poseStack.last().pose());
        matrix.translate(part.x / 16.0F, part.y / 16.0F, part.z / 16.0F);
        if (part.xRot != 0F || part.yRot != 0F || part.zRot != 0F) {
            matrix.rotate(new Quaternionf().rotationZYX(part.zRot, part.yRot, part.xRot));
        }
        if (part.xScale != 1F || part.yScale != 1F || part.zScale != 1F) {
            matrix.scale(part.xScale, part.yScale, part.zScale);
        }
        List<Box> boxes = new ArrayList<>(cubes.size());
        for (ModelPart.Cube cube : cubes) {
            // Cube 的 min/max 与顶点同单位（1/16 格），compile 时也是 /16
            boxes.add(new Box(cube.minX / 16.0F, cube.minY / 16.0F, cube.minZ / 16.0F,
                    cube.maxX / 16.0F, cube.maxY / 16.0F, cube.maxZ / 16.0F));
        }
        FRAMES.put(part, new BoneFrame(matrix, boxes));
    }

    /** 取上一帧视口渲染捕获到的骨骼数据；没有（还没渲染过 / 不是玩家模型）返回 null。 */
    public static BoneFrame get(ModelPart part) {
        return part == null ? null : FRAMES.get(part);
    }
}
