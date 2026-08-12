package net.minecraft.client.yiz.tool.health;

import java.util.Base64;

/**
 * 浮点确定性混淆（per-key 版，确定性混淆思路）。
 *
 * <p><b>为什么确定性 + 每实体 key：</b>同一实体读写用同一个 key（key 随实体存档 + 同步），
 * enc/dec 完全对称 → 不会像"静态表 + 每实体随机 noise"那样出现 noise 不匹配垃圾值。
 * key 由调用方持有（本模组实体存为 DataParameter），外部改不了 enc 值。</p>
 *
 * <p>混淆目的：血量不是明文 float（行为定位器设 testValue 看 getHealth 跟随 → 免疫）；
 * 存储是 String 非 Map/float 字段（外部扫描定位不到）。</p>
 *
 * <p>运算：rawBits ^ key → rotateLeft 13 → × MUL_CONST → 4 字节 base64url（确定性，可逆）。</p>
 */
public final class FloatObf {

    private FloatObf() {}

    private static final int ROT = 13;
    private static final int MUL_CONST = -1640531535;
    private static final int MUL_INV = 244002641;

    private static final Base64.Encoder ENC = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DEC = Base64.getUrlDecoder();

    private static final ThreadLocal<byte[]> BUF4 = ThreadLocal.withInitial(() -> new byte[4]);

    /** 混淆编码：float → base64url 字符串（key 每次传入，同实体恒定）。 */
    public static String enc(float v, int key) {
        int bits = Float.floatToRawIntBits(v);
        bits ^= key;
        bits = Integer.rotateLeft(bits, ROT);
        bits *= MUL_CONST;
        byte[] buf = BUF4.get();
        buf[0] = (byte) (bits >>> 24);
        buf[1] = (byte) (bits >>> 16);
        buf[2] = (byte) (bits >>> 8);
        buf[3] = (byte) bits;
        return ENC.encodeToString(buf);
    }

    /** 混淆解码：base64url 字符串 → float。非法输入抛异常（调用方兜底回退）。 */
    public static float dec(String s, int key) {
        byte[] b = DEC.decode(s);
        if (b.length != 4) throw new IllegalArgumentException("bad obf length");
        int bits = (b[0] & 255) << 24 | (b[1] & 255) << 16 | (b[2] & 255) << 8 | b[3] & 255;
        bits *= MUL_INV;
        bits = Integer.rotateRight(bits, ROT);
        bits ^= key;
        return Float.intBitsToFloat(bits);
    }
}
