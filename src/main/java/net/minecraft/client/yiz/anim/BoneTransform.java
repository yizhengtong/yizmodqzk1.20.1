package net.minecraft.client.yiz.anim;

/**
 * 单根骨骼相对原版姿态的增量变换：旋转（度）、位移（格）、缩放（倍率）。
 * 0 / 1 即为原版姿态，所以"没打关键帧"与"全 0 关键帧"观感一致。
 */
public record BoneTransform(
        float rx, float ry, float rz,
        float tx, float ty, float tz,
        float sx, float sy, float sz) {

    public static final BoneTransform ZERO = new BoneTransform(0F, 0F, 0F, 0F, 0F, 0F, 1F, 1F, 1F);

    public static BoneTransform rotation(float rx, float ry, float rz) {
        return new BoneTransform(rx, ry, rz, 0F, 0F, 0F, 1F, 1F, 1F);
    }

    public boolean isZero() {
        return rx == 0F && ry == 0F && rz == 0F
                && tx == 0F && ty == 0F && tz == 0F
                && sx == 1F && sy == 1F && sz == 1F;
    }

    public static BoneTransform lerp(BoneTransform a, BoneTransform b, float t) {
        if (t <= 0F) {
            return a;
        }
        if (t >= 1F) {
            return b;
        }
        return new BoneTransform(
                mixAngle(a.rx, b.rx, t), mixAngle(a.ry, b.ry, t), mixAngle(a.rz, b.rz, t),
                mix(a.tx, b.tx, t), mix(a.ty, b.ty, t), mix(a.tz, b.tz, t),
                mix(a.sx, b.sx, t), mix(a.sy, b.sy, t), mix(a.sz, b.sz, t));
    }

    /** smoothstep 插值：编辑器里比线性更顺滑。 */
    public static BoneTransform smooth(BoneTransform a, BoneTransform b, float t) {
        float x = Math.max(0F, Math.min(1F, t));
        return lerp(a, b, x * x * (3F - 2F * x));
    }

    /**
     * 角度插值走最短路径（避免 -180° 到 180° 绕一整圈），
     * 和 1.21.1 版第一人称动画的 angleLerp 行为一致。
     */
    private static float mixAngle(float from, float to, float t) {
        float diff = (to - from) % 360F;
        if (diff > 180F) {
            diff -= 360F;
        }
        if (diff < -180F) {
            diff += 360F;
        }
        return from + diff * t;
    }

    private static float mix(float from, float to, float t) {
        return from + (to - from) * t;
    }

    public BoneTransform withRotation(float rxIn, float ryIn, float rzIn) {
        return new BoneTransform(rxIn, ryIn, rzIn, tx, ty, tz, sx, sy, sz);
    }

    public BoneTransform withPosition(float txIn, float tyIn, float tzIn) {
        return new BoneTransform(rx, ry, rz, txIn, tyIn, tzIn, sx, sy, sz);
    }

    public BoneTransform withScale(float sxIn, float syIn, float szIn) {
        return new BoneTransform(rx, ry, rz, tx, ty, tz, sxIn, syIn, szIn);
    }
}
