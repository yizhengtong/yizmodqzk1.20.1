package net.minecraft.client.yiz.tool.health.codec;

/**
 * 编码值变换描述符（P0 / 存储模式 S6「编码字段」的通用抽象）。
 *
 * <p>把「存储值 w ↔ 逻辑血量 h」之间的变换抽象为可序列化描述符，全部由
 * {@link BlackBoxInverseSolver} 通过行为探针推断，不依赖任何字段名/类名/包名：</p>
 * <ul>
 *   <li>{@code PLAIN}   : h = w + b（含 b=0 的直映）；</li>
 *   <li>{@code INVERSE} : h = B − w（伤害累加器：字段越大血越少，B 为上限）；</li>
 *   <li>{@code SCALE}   : h = a·w + b（线性缩放/偏移，覆盖 maxHp 缩放存储）；</li>
 *   <li>{@code XOR}     : h = B − intBitsToFloat(rawInt(w) ^ K)（XOR 加密切片，
 *       K 由「B + 一对 (w, h)」恢复，第二探针验证）。</li>
 * </ul>
 *
 * <p>meta 序列化格式与 {@code EntityHealthLocator} 槽 meta 共用（{@code k=v;k=v}），
 * 支持落盘到 {@code entity_health_slots.json} 跨会话复用。</p>
 */
public final class EncodedValueCodec {

    private EncodedValueCodec() {}

    /** 变换类型。 */
    public enum Transform {
        PLAIN, INVERSE, SCALE, XOR, XOR_ROT
    }

    /**
     * 一个已确认的变换解。{@code a}=斜率、{@code b}=截距/上限 B、{@code key}=XOR 密钥（XOR 用）；
     * {@code key2}/{@code rotation}=密钥异或旋转变换（XOR_ROT 用）。
     */
    public record Solution(Transform transform, double a, double b, int key, int key2, int rotation) {

        public static Solution plain(double offset) {
            return new Solution(Transform.PLAIN, 1.0, offset, 0, 0, 0);
        }

        public static Solution inverse(double max) {
            return new Solution(Transform.INVERSE, -1.0, max, 0, 0, 0);
        }

        public static Solution scale(double slope, double offset) {
            return new Solution(Transform.SCALE, slope, offset, 0, 0, 0);
        }

        public static Solution xor(double max, int key) {
            return new Solution(Transform.XOR, 0.0, max, key, 0, 0);
        }

        /** 密钥异或 + 位旋转可逆混淆（通用反改血混淆形式）：h = bits( rotr( w ^ k2, r ) ^ k1 )。 */
        public static Solution xorRot(int key1, int key2, int rotation) {
            return new Solution(Transform.XOR_ROT, 0.0, 0.0, key1, key2, rotation);
        }

        /** 存储值 → 逻辑血量。 */
        public double decode(double w) {
            switch (transform) {
                case PLAIN:
                    return w + b;
                case INVERSE:
                    return b - w;
                case SCALE:
                    return a * w + b;
                case XOR: {
                    float plain = Float.intBitsToFloat(Float.floatToRawIntBits((float) w) ^ key);
                    double v = b - plain;
                    return Math.max(0.0, v);
                }
                case XOR_ROT: {
                    int raw = Integer.rotateRight(Float.floatToRawIntBits((float) w) ^ key2, rotation) ^ key;
                    return Float.intBitsToFloat(raw);
                }
                default:
                    return Double.NaN;
            }
        }

        /** 逻辑血量 → 存储值。返回 NaN 表示无解/越界。 */
        public double encode(double h) {
            switch (transform) {
                case PLAIN:
                    return h - b;
                case INVERSE:
                    return b - h;
                case SCALE:
                    return Math.abs(a) < 1e-9 ? Double.NaN : (h - b) / a;
                case XOR: {
                    double plain = b - h;
                    if (!Double.isFinite(plain) || plain < -Float.MAX_VALUE || plain > Float.MAX_VALUE) {
                        return Double.NaN;
                    }
                    return Float.intBitsToFloat(Float.floatToRawIntBits((float) plain) ^ key);
                }
                case XOR_ROT: {
                    int raw = Integer.rotateLeft(Float.floatToRawIntBits((float) h) ^ key, rotation) ^ key2;
                    return Float.intBitsToFloat(raw);
                }
                default:
                    return Double.NaN;
            }
        }

        /** 解是否自洽（参数有限）。 */
        public boolean isFinite() {
            if (transform == Transform.XOR) return Double.isFinite(b);
            if (transform == Transform.SCALE) return Double.isFinite(a) && Double.isFinite(b);
            if (transform == Transform.XOR_ROT) return rotation >= 0 && rotation < 32;
            return Double.isFinite(b);
        }

        /** 序列化到槽 meta（{@code k=v;k=v}，与 EntityHealthLocator 槽 meta 同格式）。 */
        public String toMeta() {
            return "transform=" + transform.name() + ";a=" + a + ";b=" + b + ";key=" + key
                + ";key2=" + key2 + ";rot=" + rotation;
        }

        /** 从槽 meta 反序列化；格式不符返回 null。 */
        public static Solution fromMeta(String meta) {
            if (meta == null || meta.isEmpty()) return null;
            try {
                Transform t = null;
                double a = 0.0, b = 0.0;
                int key = 0, key2 = 0, rot = 0;
                for (String part : meta.split(";")) {
                    int i = part.indexOf('=');
                    if (i <= 0) continue;
                    String k = part.substring(0, i);
                    String v = part.substring(i + 1);
                    switch (k) {
                        case "transform" -> t = Transform.valueOf(v);
                        case "a" -> a = Double.parseDouble(v);
                        case "b" -> b = Double.parseDouble(v);
                        case "key" -> key = Integer.parseInt(v);
                        case "key2" -> key2 = Integer.parseInt(v);
                        case "rot" -> rot = Integer.parseInt(v);
                        default -> { /* pair 等其它 meta 键忽略 */ }
                    }
                }
                if (t == null) return null;
                Solution s = new Solution(t, a, b, key, key2, rot);
                return s.isFinite() ? s : null;
            } catch (Throwable ex) {
                return null;
            }
        }
    }
}
