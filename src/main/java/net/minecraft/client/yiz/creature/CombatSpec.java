package net.minecraft.client.yiz.creature;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.Map;
import java.util.Optional;

/**
 * 战斗参数组件值 —— 描述生物「怎么打」。
 *
 * <p>所有字段可选：未配置时由调用方回退到实体自身的硬编码常量。
 * 这样组件化可以渐进推进，不必一次性把所有实体的数值搬进配置，避免手感回归。</p>
 *
 * @param attackInterval 攻击间隔（tick）
 * @param attackRange    攻击距离（格）
 * @param skillId        技能标识（派发到实体侧已有实现）
 * @param skillParams    技能参数（半径、倍率等，按 key 取）
 */
public record CombatSpec(
    Optional<Integer> attackInterval,
    Optional<Double> attackRange,
    Optional<String> skillId,
    Map<String, Double> skillParams
) {

    public static final CombatSpec EMPTY =
        new CombatSpec(Optional.empty(), Optional.empty(), Optional.empty(), Map.of());

    public static final Codec<CombatSpec> CODEC = RecordCodecBuilder.create(inst -> inst.group(
        Codec.INT.optionalFieldOf("attack_interval").forGetter(CombatSpec::attackInterval),
        Codec.DOUBLE.optionalFieldOf("attack_range").forGetter(CombatSpec::attackRange),
        Codec.STRING.optionalFieldOf("skill_id").forGetter(CombatSpec::skillId),
        Codec.unboundedMap(Codec.STRING, Codec.DOUBLE)
            .optionalFieldOf("skill_params", Map.of()).forGetter(CombatSpec::skillParams)
    ).apply(inst, CombatSpec::new));

    /** 攻击间隔；未配置返回 fallback。 */
    public int intervalOr(int fallback) {
        return attackInterval.orElse(fallback);
    }

    /** 攻击距离；未配置返回 fallback。 */
    public double rangeOr(double fallback) {
        return attackRange.orElse(fallback);
    }

    /** 技能标识；未配置返回 fallback。 */
    public String skillIdOr(String fallback) {
        return skillId.orElse(fallback);
    }

    /** 技能参数；未配置返回 fallback。 */
    public double paramOr(String key, double fallback) {
        Double v = skillParams.get(key);
        return v != null ? v : fallback;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 链式构造（代码轨注册用）。 */
    public static final class Builder {
        private Integer attackInterval;
        private Double attackRange;
        private String skillId;
        private final Map<String, Double> skillParams = new java.util.LinkedHashMap<>();

        public Builder attackInterval(int v) {
            this.attackInterval = v;
            return this;
        }

        public Builder attackRange(double v) {
            this.attackRange = v;
            return this;
        }

        public Builder skillId(String v) {
            this.skillId = v;
            return this;
        }

        public Builder param(String key, double v) {
            this.skillParams.put(key, v);
            return this;
        }

        public CombatSpec build() {
            return new CombatSpec(
                Optional.ofNullable(attackInterval),
                Optional.ofNullable(attackRange),
                Optional.ofNullable(skillId),
                Map.copyOf(skillParams));
        }
    }
}
