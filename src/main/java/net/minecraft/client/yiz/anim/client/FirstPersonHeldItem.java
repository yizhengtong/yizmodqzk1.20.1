package net.minecraft.client.yiz.anim.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.model.ItemTransform;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.yiz.anim.AnimClientState;
import net.minecraft.client.yiz.anim.AnimLibrary;
import net.minecraft.client.yiz.anim.BoneTransform;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * 第一人称手持物品渲染（思路参考 1.21.1 版 yizxianmod 的 FirstPersonSwordRenderer）。
 *
 * <p><b>为什么不用手臂骨骼</b>：第一人称手部是相机空间的一堆固定变换，手臂模型的坐标轴和
 * "物品在视野里怎么摆"没有直观对应。这里改成和原版/Blockbench 完全同一套坐标：</p>
 * <ol>
 *   <li>先把物品放到掌心锚点 {@code (±0.56, -0.52, -0.72)}（原版 applyItemArmTransform 的基准位置）；</li>
 *   <li>套用物品自己的 {@code firstperson_righthand/lefthand} display transform
 *       （旋转角度、1/16 格平移、缩放，和 Blockbench 的 display 设置一模一样）；</li>
 *   <li>动画骨骼 {@code fp_*_arm} 作为这个 display transform 的<b>增量</b>：
 *       旋转相加、平移按格相加、缩放相乘（0 增量 = 原版第一人称姿势）；</li>
 *   <li>用 {@link ItemDisplayContext#NONE} 渲染物品，原版就不会再套一次 display。</li>
 * </ol>
 *
 * <p>{@code fp_*_item} 是 display transform 之后的物品局部额外变换（次要微调），左右手镜像。</p>
 */
public final class FirstPersonHeldItem {

    /** 原版第一人称手心锚点（相机空间，右手；左手 x 取反）。 */
    private static final float ANCHOR_X = 0.56F;
    private static final float ANCHOR_Y = -0.52F;
    private static final float ANCHOR_Z = -0.72F;

    private FirstPersonHeldItem() {}

    /** 这只手在视觉上是左手还是右手。 */
    public static boolean isLeftSide(LocalPlayer player, InteractionHand hand) {
        HumanoidArm arm = hand == InteractionHand.MAIN_HAND
                ? player.getMainArm()
                : player.getMainArm().getOpposite();
        return arm == HumanoidArm.LEFT;
    }

    /**
     * 游戏内渲染：只有<b>第一人称动画</b>给这只手写了 {@code fp_*_arm} 关键帧时才接管（返回 true 并取消原版），
     * 否则返回 false 走原版第一人称（空手手臂、弓/地图等特殊物品不受影响）。
     *
     * <p>注意：这里只查第一人称播放态，第三人称动画完全不会影响第一人称；
     * 物品没配置 {@code first_person} 时第一人称永远是原版。</p>
     */
    public static boolean renderAnimated(PoseStack pose, ItemInHandRenderer renderer, LocalPlayer player,
                                         ItemStack stack, boolean leftHand, MultiBufferSource buffer, int light) {
        if (stack.isEmpty()) {
            return false;
        }
        // 当前手里的物品必须配置了第一人称动画；播放中切换物品会立刻回到原版
        if (!AnimLibrary.hasFirstPerson(stack)) {
            return false;
        }
        if (!AnimClientState.hasFirstPersonKeyframes(leftHand ? "fp_left_arm" : "fp_right_arm")) {
            return false;
        }
        render(pose, renderer, player, stack, leftHand, buffer, light);
        return true;
    }

    /**
     * 编辑器预览：不管有没有关键帧都按当前第一人称姿势渲染（0 增量 = 原版姿势），
     * 这样打开第一人称模式就能看到物品本来在手里的样子。
     */
    public static void renderPreview(PoseStack pose, ItemInHandRenderer renderer, LocalPlayer player,
                                     ItemStack stack, boolean leftHand, MultiBufferSource buffer, int light) {
        if (!stack.isEmpty()) {
            render(pose, renderer, player, stack, leftHand, buffer, light);
        }
    }

    private static void render(PoseStack pose, ItemInHandRenderer renderer, LocalPlayer player,
                               ItemStack stack, boolean leftHand, MultiBufferSource buffer, int light) {
        ItemTransform transform = resolveTransform(player, stack, leftHand);
        pose.pushPose();
        pose.translate(leftHand ? -ANCHOR_X : ANCHOR_X, ANCHOR_Y, ANCHOR_Z);
        transform.apply(leftHand, pose);
        applyItemBone(pose, player, leftHand);
        // NONE：原版不再套用 display transform，上面已经手动套过了
        renderer.renderItem(player, stack, ItemDisplayContext.NONE, leftHand, pose, buffer, light);
        pose.popPose();
    }

    /**
     * fp_*_arm 是<b>绝对</b>的 firstperson display transform（和 Blockbench 的
     * firstperson_righthand/lefthand 完全一致，可直接把 a1~a4 文件的 display 数值填进来）：
     * 有键就用关键帧本身，没键（编辑器预览的初始状态）才退回物品自带的 display。
     */
    private static ItemTransform resolveTransform(LocalPlayer player, ItemStack stack, boolean leftHand) {
        String bone = leftHand ? "fp_left_arm" : "fp_right_arm";
        if (!AnimClientState.hasFirstPersonKeyframes(bone)) {
            return baseTransform(player, stack, leftHand);
        }
        BoneTransform t = AnimClientState.sampleFirstPerson(bone);
        return new ItemTransform(
                new Vector3f(t.rx(), t.ry(), t.rz()),
                new Vector3f(t.tx(), t.ty(), t.tz()),
                new Vector3f(t.sx(), t.sy(), t.sz()));
    }

    /** 物品自己的第一人称 display transform（模型 JSON 里 firstperson_righthand/lefthand）。 */
    private static ItemTransform baseTransform(LocalPlayer player, ItemStack stack, boolean leftHand) {
        BakedModel model = Minecraft.getInstance().getItemRenderer()
                .getModel(stack, player.level(), player, player.getId());
        ItemDisplayContext ctx = leftHand
                ? ItemDisplayContext.FIRST_PERSON_LEFT_HAND
                : ItemDisplayContext.FIRST_PERSON_RIGHT_HAND;
        return model.getTransforms().getTransform(ctx);
    }

    /** fp_*_item：display transform 之后的物品局部额外变换，左右镜像保证同参数对称。 */
    private static void applyItemBone(PoseStack pose, LocalPlayer player, boolean leftHand) {
        String bone = leftHand ? "fp_left_item" : "fp_right_item";
        if (!AnimClientState.hasFirstPersonKeyframes(bone)) {
            return;
        }
        BoneTransform t = AnimClientState.sampleFirstPerson(bone);
        if (t.isZero()) {
            return;
        }
        float xSign = leftHand ? -1F : 1F;
        float yzSign = leftHand ? -1F : 1F;
        pose.translate(xSign * t.tx(), t.ty(), t.tz());
        pose.mulPose(new Quaternionf().rotationXYZ(
                (float) Math.toRadians(t.rx()),
                (float) Math.toRadians(yzSign * t.ry()),
                (float) Math.toRadians(yzSign * t.rz())));
        pose.scale(t.sx(), t.sy(), t.sz());
    }
}
