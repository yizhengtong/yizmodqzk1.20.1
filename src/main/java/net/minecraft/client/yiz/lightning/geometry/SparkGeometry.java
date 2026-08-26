package net.minecraft.client.yiz.lightning.geometry;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * 火花几何 — billboard quad 沿 velocity 方向拉长做拖尾，面朝相机。
 *
 * <p><b>拖尾方向</b>：把世界 velocity 经 {@code camRot.transformInverse}（世界→相机本地）
 * 投影到屏幕平面取 {@code dir2D = normalize(vCam.xy)} 作拖尾轴，垂直方向收窄。velocity≈0 时
 * 退化为对称亮点。拖尾长度随速度增长（封顶 2×）。</p>
 *
 * <p><b>UV 编码方向</b>：quad 的 v 轴对齐运动方向——vUV.y=0 为头端（+T，运动前方，亮）、
 * vUV.y=1 为尾端（-T，身后，暗），配合 {@code lightning_spark.fsh} 的 {@code tailFalloff} 出各向异性。</p>
 *
 * <p><b>运动位移</b>：位置 = 初始 pos + velocity·elapsed + 重力（½gt²）。{@code SparkEffect} 是纯数据，
 * 位移在此算（elapsed = maxLife - life，由 renderer 传入）。</p>
 */
public final class SparkGeometry {

    private SparkGeometry() {}

    /**
     * 渲染单颗火花写入 vc。
     *
     * @param camRot   相机旋转（{@code event.getCamera().rotation()}，把相机本地转世界）
     * @param posRel   初始位置（相机相对坐标）
     * @param velocity 世界速度（格/秒）
     * @param elapsed  已存活秒（maxLife - life）
     * @param gravity  重力加速度（格/秒²，向下）
     */
    public static void emitSpark(VertexConsumer vc, Matrix4f mat, Quaternionf camRot,
                                 Vec3 posRel, Vec3 velocity, float halfSize,
                                 float r, float g, float b, float alpha, float elapsed, float gravity) {
        // 当前位置 = 初始 + 匀速位移 + 重力位移（½gt²）
        double ex = velocity.x * elapsed;
        double ey = velocity.y * elapsed - 0.5 * gravity * elapsed * elapsed;
        double ez = velocity.z * elapsed;
        Vec3 cur = posRel.add(ex, ey, ez);

        // velocity 转相机空间（camRot：相机本地→世界，故用 transformInverse 取逆）
        Vector3f vCam = new Vector3f((float) velocity.x, (float) velocity.y, (float) velocity.z);
        camRot.transformInverse(vCam);

        // 屏幕拖尾方向 = 相机空间 xy 投影
        float vx = vCam.x, vy = vCam.y;
        float vl = (float) Math.sqrt(vx * vx + vy * vy);
        float dx, dy;
        if (vl < 1e-4f) { dx = 1f; dy = 0f; }        // 无方向 → 对称亮点
        else { dx = vx / vl; dy = vy / vl; }

        // 拖尾长度随速度增长（封顶 2×）
        float speed = (float) velocity.length();
        float tail = halfSize * (1.5f + Math.min(speed * 0.3f, 2.0f));

        // 拖尾轴 T、垂直轴 P（相机本地→世界，camRot.transform 原地变换）
        Vector3f axisT = new Vector3f(dx * tail, dy * tail, 0f);
        Vector3f axisP = new Vector3f(-dy * halfSize, dx * halfSize, 0f);
        camRot.transform(axisT);
        camRot.transform(axisP);

        // 4 顶点 quad：+T = 头端(运动前方,v=0亮)，-T = 尾端(身后,v=1暗)
        //   绕序：头左 → 头右 → 尾右 → 尾左（disableCull 下绕序不影响）
        vert(vc, mat, cur, axisT, axisP, +1, +1, 0f, 0f, r, g, b, alpha);
        vert(vc, mat, cur, axisT, axisP, +1, -1, 1f, 0f, r, g, b, alpha);
        vert(vc, mat, cur, axisT, axisP, -1, -1, 1f, 1f, r, g, b, alpha);
        vert(vc, mat, cur, axisT, axisP, -1, +1, 0f, 1f, r, g, b, alpha);
    }

    private static void vert(VertexConsumer vc, Matrix4f m, Vec3 center,
                             Vector3f axisT, Vector3f axisP,
                             int sT, int sP, float u, float v,
                             float r, float g, float b, float a) {
        float x = (float) center.x + axisT.x * sT + axisP.x * sP;
        float y = (float) center.y + axisT.y * sT + axisP.y * sP;
        float z = (float) center.z + axisT.z * sT + axisP.z * sP;
                vc.vertex(m, x, y, z).uv(u, v)
            .color((int)(r*255), (int)(g*255), (int)(b*255), (int)(a*255)).endVertex();
    }
}
