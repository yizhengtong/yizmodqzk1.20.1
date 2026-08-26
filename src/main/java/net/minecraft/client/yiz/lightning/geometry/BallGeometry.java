package net.minecraft.client.yiz.lightning.geometry;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * 真实 3D 球体几何（UV sphere 经纬度网格）— 非 billboard，从任意视角都是真 3D 体积球。
 *
 * <p>顶点局部坐标 = radius × 球面方向，vsh 据此 normalize 出球面法线做光照。
 * quad 绕序 CCW-from-outside（外面 = front face），配合 enableCull 只画外半球。</p>
 */
public final class BallGeometry {

    private static final int LAT_SEG = 12;  // 纬度段（-90°..90°）
    private static final int LON_SEG = 16;  // 经度段（0..360°），LAT×LON = 192 面

    private BallGeometry() {}

    /** 生成 UV sphere 写入 vc（PoseStack 已 translate 到球心）。UV = 经纬度归一化，供 fsh 球面电流用。 */
    public static void emitBall(VertexConsumer vc, Matrix4f mat, float radius,
                                float r, float g, float b, float a) {
        for (int li = 0; li < LAT_SEG; li++) {
            float t0 = (float) li / LAT_SEG, t1 = (float) (li + 1) / LAT_SEG;
            double lat0 = (t0 - 0.5) * Math.PI, lat1 = (t1 - 0.5) * Math.PI;
            for (int lj = 0; lj < LON_SEG; lj++) {
                float s0 = (float) lj / LON_SEG, s1 = (float) (lj + 1) / LON_SEG;
                double lon0 = s0 * 2 * Math.PI, lon1 = s1 * 2 * Math.PI;
                Vec3 p00 = sp(radius, lat0, lon0);
                Vec3 p10 = sp(radius, lat1, lon0);
                Vec3 p11 = sp(radius, lat1, lon1);
                Vec3 p01 = sp(radius, lat0, lon1);
                // 绕序使外法线面 = front face（enableCull 剔除背面内壁，画外半球含核心）
                vert(vc, mat, p00, s0, t0, r, g, b, a);
                vert(vc, mat, p01, s1, t0, r, g, b, a);
                vert(vc, mat, p11, s1, t1, r, g, b, a);
                vert(vc, mat, p10, s0, t1, r, g, b, a);
            }
        }
    }

    private static Vec3 sp(float r, double lat, double lon) {
        double cy = Math.cos(lat);
        return new Vec3(r * cy * Math.cos(lon), r * Math.sin(lat), r * cy * Math.sin(lon));
    }

    private static void vert(VertexConsumer vc, Matrix4f m, Vec3 p,
                             float u, float v, float r, float g, float b, float a) {
                vc.vertex(m, (float) p.x, (float) p.y, (float) p.z).uv(u, v)
            .color((int)(r*255), (int)(g*255), (int)(b*255), (int)(a*255)).endVertex();
    }
}
