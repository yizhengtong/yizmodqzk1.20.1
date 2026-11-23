package net.minecraft.client.yiz.anim.client;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.yiz.anim.AnimClientState;
import net.minecraft.client.yiz.anim.AnimClip;
import net.minecraft.client.yiz.anim.BoneTransform;
import net.minecraft.world.entity.player.Player;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * 把动画骨骼姿态应用到玩家模型（{@code PlayerModel}）上。
 *
 * <h2>动画骨骼用"绝对姿态"，不再叠加原版动作</h2>
 * <p>只要当前动画对某根骨骼有关键帧，这根骨骼的<b>旋转就直接取动画值</b>（位移仍在原版枢轴上叠加）。
 * 原版的走路摆臂、攻击挥臂、secondary 摆动都不会混进来，所以：</p>
 * <ul>
 *   <li>每段动画的第一帧就是编辑器里看到的那个初始姿态，不会出现"从上一段/上一挥的姿势过渡过来"的中间帧；</li>
 *   <li>动画之间的切换是硬切（配合 {@code AnimClientState} 的队列，上一段播完立刻换下一段）；</li>
 *   <li>没有被当前动画写关键帧的骨骼完全不碰，仍然走原版姿态。</li>
 * </ul>
 *
 * <h2>为什么需要保存/还原原版字段</h2>
 * <p>原版 {@code setupAnim} 并不会重设所有字段（{@code body.zRot}、位移、缩放等尤其不会），
 * 而绝对姿态会覆盖旋转；所以在 TAIL 覆盖前先把原版值存下来，下一帧 HEAD 原样写回，
 * 让原版 {@code setupAnim} 从干净状态开始，既不累积也不会残留上一段动画的姿势。</p>
 */
public final class AnimApplier {

    /** 本帧被覆盖过的骨骼 → 覆盖前的原版字段 {x,y,z, xScale,yScale,zScale, xRot,yRot,zRot}。 */
    private static final Map<ModelPart, float[]> VANILLA = new IdentityHashMap<>();

    private AnimApplier() {}

    /** 在 {@code PlayerModel#setupAnim} 的 HEAD 调用：把上一帧覆盖过的字段还原成原版值。 */
    public static void restore(HumanoidModel<?> model) {
        restorePart(model.body);
        restorePart(model.head);
        restorePart(model.rightArm);
        restorePart(model.leftArm);
        restorePart(model.rightLeg);
        restorePart(model.leftLeg);
    }

    private static void restorePart(ModelPart part) {
        if (part == null) {
            return;
        }
        float[] saved = VANILLA.remove(part);
        if (saved == null) {
            return;
        }
        part.x = saved[0];
        part.y = saved[1];
        part.z = saved[2];
        part.xScale = saved[3];
        part.yScale = saved[4];
        part.zScale = saved[5];
        part.xRot = saved[6];
        part.yRot = saved[7];
        part.zRot = saved[8];
    }

    public static void apply(HumanoidModel<?> model, Player player) {
        AnimClip clip = AnimClientState.currentClip(player.getId());
        if (clip == null) {
            return;
        }
        BoneTransform body = AnimClientState.sample(player.getId(), "body");
        BoneTransform head = AnimClientState.sample(player.getId(), "head");
        BoneTransform rightArm = AnimClientState.sample(player.getId(), "right_arm");
        BoneTransform leftArm = AnimClientState.sample(player.getId(), "left_arm");
        BoneTransform rightLeg = AnimClientState.sample(player.getId(), "right_leg");
        BoneTransform leftLeg = AnimClientState.sample(player.getId(), "left_leg");

        float bodyYaw = body.ry();
        applyPart(model.body, "body", body, 0F, clip);
        applyPart(model.head, "head", head, bodyYaw, clip);
        applyPart(model.rightArm, "right_arm", rightArm, bodyYaw, clip);
        applyPart(model.leftArm, "left_arm", leftArm, bodyYaw, clip);
        applyPart(model.rightLeg, "right_leg", rightLeg, bodyYaw, clip);
        applyPart(model.leftLeg, "left_leg", leftLeg, bodyYaw, clip);
    }

    private static void applyPart(ModelPart part, String bone, BoneTransform t, float extraYaw, AnimClip clip) {
        if (part == null || !clip.hasKeyframes(bone)) {
            // 这段动画没动这根骨骼：保持原版姿态，不保存也不覆盖
            return;
        }
        VANILLA.put(part, new float[]{
                part.x, part.y, part.z,
                part.xScale, part.yScale, part.zScale,
                part.xRot, part.yRot, part.zRot});

        // 位移/缩放：枢轴和缩放基准是模型自身的一部分，仍在原版值上叠加
        part.x += t.tx() * 16.0F;
        part.y += t.ty() * 16.0F;
        part.z += t.tz() * 16.0F;
        part.xScale *= t.sx();
        part.yScale *= t.sy();
        part.zScale *= t.sz();

        // 旋转：绝对姿态，完全由动画决定（原版走路/攻击/摆动不参与）
        part.xRot = (float) Math.toRadians(t.rx());
        part.yRot = (float) Math.toRadians(t.ry() + extraYaw);
        part.zRot = (float) Math.toRadians(t.rz());
    }
}
