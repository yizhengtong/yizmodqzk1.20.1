package net.minecraft.client.yiz.tool.health.codec;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeMap;
import net.minecraft.world.entity.ai.attributes.Attributes;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 黑箱反函数求解器（P0 / 存储模式 S6「编码字段」）。
 *
 * <p>不猜编码、不认字段名：把「存储值 → getHealth」当作黑箱，用少量探针
 * （写 → 读 → 还原，全部在同一 tick 内完成）推断变换类型并恢复参数，
 * 之后即可对任意逻辑血量目标反解存储值：</p>
 * <ul>
 *   <li>线性响应（斜率 ≈ ±1）→ {@code PLAIN} / {@code INVERSE}（B = h + w 直接恢复）；</li>
 *   <li>非 ±1 的线性响应 → {@code SCALE}（两点定斜率、第三点线性验证）；</li>
 *   <li>混沌但确定（XOR 切片：±1 写入响应微小/乱序）→ 以 AttributeMap 里
 *       {@code MAX_HEALTH} 基值为 B，由一对 (w, h) 恢复
 *       {@code K = rawInt(w) ^ rawInt(B − h)}，再用独立探针点验证。</li>
 * </ul>
 *
 * <p>所有探针写后立即还原，且不触发目标任何方法（直接改字段/DataItem 值），
 * 不依赖目标模组任何名字。</p>
 */
public final class BlackBoxInverseSolver {

    /** 单次求解最多探针次数（写-读-还原算一次）。 */
    private static final int MAX_PROBES = 8;

    private BlackBoxInverseSolver() {}

    /** 存储槽访问抽象（普通字段 / DataItem / 任意数值通道）。 */
    public interface StorageAccess {
        /** 读当前存储值。失败返回 NaN。 */
        double read();

        /** 写存储值。返回是否成功。 */
        boolean write(double v);
    }

    /**
     * 推断实体某一存储槽的变换。
     *
     * @param entity 目标实体（getHealth 作为逻辑血量 oracle）
     * @param access 存储槽访问器
     * @return 已确认的解；无法确认返回 null（调用方不缓存）
     */
    public static EncodedValueCodec.Solution infer(LivingEntity entity, StorageAccess access) {
        if (entity == null || access == null) return null;
        double w0, h0;
        try {
            w0 = access.read();
            h0 = entity.getHealth();
        } catch (Throwable t) {
            return null;
        }
        if (!Double.isFinite(w0) || !Double.isFinite(h0) || h0 <= 0) return null;

        double d = probeDelta(w0, h0);
        if (!(d > 0)) return null;

        // 探针 1/2：w0 ± d
        double hPlus = probe(entity, access, w0 + d, w0);
        double hMinus = probe(entity, access, w0 - d, w0);
        if (!Double.isFinite(hPlus) || !Double.isFinite(hMinus)) {
            return inferXor(entity, access, w0, h0, d);
        }
        double slopePlus = (hPlus - h0) / d;
        double slopeMinus = (hMinus - h0) / (-d);
        if (!Double.isFinite(slopePlus) || !Double.isFinite(slopeMinus)) {
            return inferXor(entity, access, w0, h0, d);
        }

        // 几乎无响应（|Δh| 极小）→ 疑似 XOR 切片（±1 只翻最低位 → 血量微变）
        if (Math.abs(slopePlus) < 0.05 && Math.abs(slopeMinus) < 0.05) {
            return inferXor(entity, access, w0, h0, d);
        }

        double slope = (slopePlus + slopeMinus) / 2.0;
        // 方向不对称（正负斜率不一致）→ 混沌响应 → XOR 候选
        if (Math.abs(slopePlus - slopeMinus) > Math.max(0.25, Math.abs(slope) * 0.4)) {
            return inferXor(entity, access, w0, h0, d);
        }

        // 第三点线性验证：w0 + 2d
        double h2 = probe(entity, access, w0 + 2 * d, w0);
        if (Double.isFinite(h2)) {
            double expect = h0 + slope * 2 * d;
            if (Math.abs(h2 - expect) > Math.max(1.0, Math.abs(expect) * 0.1)) {
                return inferXor(entity, access, w0, h0, d);
            }
        }

        double b = h0 - slope * w0;
        if (Math.abs(slope - 1.0) <= 0.05) {
            return EncodedValueCodec.Solution.plain(b);
        }
        if (Math.abs(slope + 1.0) <= 0.05) {
            return EncodedValueCodec.Solution.inverse(b);
        }
        if (Math.abs(slope) >= 0.01 && Math.abs(slope) <= 100.0 && Double.isFinite(b)) {
            return EncodedValueCodec.Solution.scale(slope, b);
        }
        return null;
    }

    /**
     * 用已确认的解把逻辑血量写到目标值（反解存储值并写回，带写后验证）。
     *
     * @return 是否写入且 getHealth 到达目标（容差内）
     */
    public static boolean writeTo(LivingEntity entity, StorageAccess access,
                                  EncodedValueCodec.Solution sol, double targetHealth) {
        if (entity == null || access == null || sol == null || !sol.isFinite()) return false;
        double t = Math.max(0.0, targetHealth);
        // 上限钳制：INVERSE/XOR 的 b 即最大血量 B；PLAIN/SCALE 无自然上限，交给实体自身钳制
        if (sol.transform() == EncodedValueCodec.Transform.INVERSE
                || sol.transform() == EncodedValueCodec.Transform.XOR) {
            t = Math.min(t, sol.b());
        }
        double w = sol.encode(t);
        if (!Double.isFinite(w)) return false;
        boolean wrote;
        try {
            wrote = access.write(w);
        } catch (Throwable t2) {
            return false;
        }
        if (!wrote) return false;
        double after;
        try {
            after = entity.getHealth();
        } catch (Throwable t2) {
            return false;
        }
        if (!Double.isFinite(after)) return false;
        // XOR/INVERSE 为精确变换：写后必须精确到达目标（±0.5 防 float 舍入）
        return Math.abs(after - t) <= 0.5;
    }

    // ==================== 探针 ====================

    /** 写值 → 读 getHealth → 还原。返回读取到的逻辑血量；任何一步失败返回 NaN。 */
    private static double probe(LivingEntity entity, StorageAccess access, double writeVal, double restoreVal) {
        try {
            if (!access.write(writeVal)) return Double.NaN;
            double h = entity.getHealth();
            access.write(restoreVal);
            return h;
        } catch (Throwable t) {
            try {
                access.write(restoreVal);
            } catch (Throwable ignored) {}
            return Double.NaN;
        }
    }

    private static double probeDelta(double w0, double h0) {
        double d = Math.max(1.0, Math.abs(w0) * 1e-3);
        d = Math.min(d, 1e6);
        // 探针幅度相对逻辑血量也不宜过大（防跨死亡阈值）
        double hd = Math.max(1.0, h0 * 1e-3);
        return Math.max(1.0, Math.min(d, hd));
    }

    // ==================== XOR_ROT 密钥候选 + 反推求解 ====================

    /** 密钥候选：具体 int 值 + 来源（用于跨实体重推与元数据序列化）。 */
    public record KeyCandidate(int value, EncodedValueCodec.KeySource source) {}

    /**
     * 从实体身份特征派生通用密钥候选集（去重、确定性顺序）。
     * 候选值来自 {@link EncodedValueCodec.KeySource#resolve}，不再硬编码 {@code {0, hash, -hash}}。
     */
    public static List<KeyCandidate> deriveKeyCandidates(LivingEntity entity) {
        List<KeyCandidate> out = new ArrayList<>();
        if (entity == null) return out;
        LinkedHashSet<Integer> seen = new LinkedHashSet<>();
        for (EncodedValueCodec.KeySource src : EncodedValueCodec.KeySource.values()) {
            try {
                int v = src.resolve(entity);
                if (seen.add(v)) out.add(new KeyCandidate(v, src));
            } catch (Throwable ignored) {}
        }
        return out;
    }

    /**
     * 纯求解 XOR_ROT 密钥（软预筛，不确认）：枚举 r(32) × 每个候选 k1，k2 由公式
     * {@code k2 = enc ^ rotl(raw ^ k1, r)} 唯一反推，k2 命中候选集则产出候选解。
     *
     * <p>注意：「编码往返验证」是数学冗余（等式成立时 encode(h)==enc 恒成立），不能当假阳性
     * 过滤器；满血实体上 raw==maxHealth bits 会使 k2 坍缩为 enc。权威确认必须由调用方用
     * 独立写探针（写 → 读 getHealth → 还原）完成，本方法只做软预筛。</p>
     *
     * @param enc        存储的加密整数（从字符串 token 提取）
     * @param raw        逻辑血量的 {@code floatToRawIntBits}
     * @param candidates 密钥候选集（{@link #deriveKeyCandidates}）
     * @return 候选解列表（可能为空）；每个解携带密钥源 keySrc1/keySrc2
     */
    public static List<EncodedValueCodec.Solution> solveKeyedRotation(
            int enc, int raw, List<KeyCandidate> candidates) {
        List<EncodedValueCodec.Solution> out = new ArrayList<>();
        if (candidates == null || candidates.isEmpty()) return out;
        Map<Integer, EncodedValueCodec.KeySource> byValue = new HashMap<>();
        for (KeyCandidate c : candidates) {
            byValue.putIfAbsent(c.value(), c.source());  // value 去重后唯一，保留确定性顺序首个源
        }
        for (int r = 0; r < 32; r++) {
            for (KeyCandidate k1 : candidates) {
                int k2 = enc ^ Integer.rotateLeft(raw ^ k1.value(), r);
                EncodedValueCodec.KeySource k2src = byValue.get(k2);
                if (k2src == null) continue;
                out.add(EncodedValueCodec.Solution.xorRotKeyed(k1.source(), k2src, r, k1.value(), k2));
            }
        }
        return out;
    }

    /**
     * XOR 候选：B 取 AttributeMap 中 MAX_HEALTH 基值（绕过 getAttribute 换柱/覆盖），
     * K = rawInt(w0) ^ rawInt(B − h0)，再用独立探针点（h0 ± d）验证。
     */
    private static EncodedValueCodec.Solution inferXor(LivingEntity entity, StorageAccess access,
                                                       double w0, double h0, double d) {
        double B = maxHealthBase(entity);
        if (!Double.isFinite(B) || B <= 0) return null;
        double plain0 = B - h0;
        if (!Double.isFinite(plain0) || plain0 < 0 || plain0 > Float.MAX_VALUE) return null;

        int key = Float.floatToRawIntBits((float) w0) ^ Float.floatToRawIntBits((float) plain0);

        // 验证点 1：h0 + d（向上；超出上限则改向下）
        double target = h0 + d;
        if (target >= B) target = h0 - d;
        if (target <= 0 || target >= B) return null;
        if (!verifyXorPoint(entity, access, B, key, target)) return null;

        // 验证点 2（反向，可选）：h0 − d
        double target2 = h0 - d;
        if (target2 > 0 && target2 < B) {
            if (!verifyXorPoint(entity, access, B, key, target2)) return null;
        }
        return EncodedValueCodec.Solution.xor(B, key);
    }

    /** 验证 XOR 解在目标血量处的写-读往返（写 w → 读 getHealth → 还原 w0）。
     *  XOR 变换是精确自反的（h = B − intBitsToFloat(rawInt(w)^K)），验证容差必须近似精确
     *  （±0.5 内）——否则无关字段「写入后血量纹丝不动」也会被宽松容差放行成假阳性。 */
    private static boolean verifyXorPoint(LivingEntity entity, StorageAccess access,
                                          double B, int key, double target) {
        double plain = B - target;
        if (!Double.isFinite(plain) || plain < 0 || plain > Float.MAX_VALUE) return false;
        float w = Float.intBitsToFloat(Float.floatToRawIntBits((float) plain) ^ key);
        double w0;
        try {
            w0 = access.read();
        } catch (Throwable t) {
            return false;
        }
        if (!Double.isFinite(w0)) return false;
        double hV = probe(entity, access, w, w0);
        if (!Double.isFinite(hV)) return false;
        // 精确容差：写 w 后 getHealth 必须精确到达 target（±0.5 防 float 舍入）
        return Math.abs(hV - target) <= 0.5;
    }

    // ==================== B（逻辑上限）恢复 ====================

    /**
     * 逻辑血量上限 B：优先读 AttributeMap 里 {@code MAX_HEALTH} 实例的基值
     * （反射 map 字段，绕过实体对 {@code getAttribute(MAX_HEALTH)} 的换柱/覆盖），
     * 失败回退 {@code getMaxHealth()}。
     */
    public static double maxHealthBase(LivingEntity entity) {
        try {
            AttributeMap map = entity.getAttributes();
            if (map != null) {
                for (Field f : AttributeMap.class.getDeclaredFields()) {
                    if (!Map.class.isAssignableFrom(f.getType())) continue;
                    f.setAccessible(true);
                    Object m = f.get(map);
                    if (!(m instanceof Map<?, ?> mm)) continue;
                    for (Map.Entry<?, ?> e : mm.entrySet()) {
                        if (e.getKey() == Attributes.MAX_HEALTH && e.getValue() instanceof AttributeInstance ai) {
                            double v = ai.getBaseValue();
                            if (v > 0 && Double.isFinite(v)) return v;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
        try {
            double v = entity.getMaxHealth();
            if (v > 0 && Double.isFinite(v)) return v;
        } catch (Throwable ignored) {}
        return Double.NaN;
    }
}
