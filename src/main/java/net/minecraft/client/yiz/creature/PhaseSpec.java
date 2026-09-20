package net.minecraft.client.yiz.creature;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 形态定义 —— 多形态生物的一个阶段。
 *
 * <p>每个形态自带三样东西：</p>
 * <ol>
 *   <li><b>本形态的变化</b>：{@link #attributes}（属性）+ {@link #combat}（战斗参数）；</li>
 *   <li><b>进入下一形态的条件</b>：{@link #advance}；</li>
 *   <li><b>回退到上一形态的条件</b>：{@link #regress}。</li>
 * </ol>
 *
 * <p>最后一个形态的 {@code advance} 与第一个形态的 {@code regress} 留空即可。
 * 判定由实体侧驱动（读血量比例 / 形态计时 / 战斗计时），本类只描述规则。</p>
 *
 * @param index      形态序号（从 1 起）
 * @param attributes 本形态下要写入的属性（{@code yizmodqzk:} 命名空间）
 * @param combat     本形态的战斗参数
 * @param advance    进入下一形态的条件
 * @param regress    回退到上一形态的条件
 */
public record PhaseSpec(
    int index,
    Map<ResourceLocation, Double> attributes,
    CombatSpec combat,
    Optional<PhaseTrigger> advance,
    Optional<PhaseTrigger> regress
) {

    public static final Codec<PhaseSpec> CODEC = RecordCodecBuilder.create(inst -> inst.group(
        Codec.INT.fieldOf("index").forGetter(PhaseSpec::index),
        Codec.unboundedMap(ResourceLocation.CODEC, Codec.DOUBLE)
            .optionalFieldOf("attributes", Map.of()).forGetter(PhaseSpec::attributes),
        CombatSpec.CODEC.optionalFieldOf("combat", CombatSpec.EMPTY).forGetter(PhaseSpec::combat),
        PhaseTrigger.CODEC.optionalFieldOf("advance").forGetter(PhaseSpec::advance),
        PhaseTrigger.CODEC.optionalFieldOf("regress").forGetter(PhaseSpec::regress)
    ).apply(inst, PhaseSpec::new));

    public static final Codec<List<PhaseSpec>> LIST_CODEC = CODEC.listOf();

    /** 按属性 path 取值；未配置返回 fallback。 */
    public Double attr(String path, Double fallback) {
        for (Map.Entry<ResourceLocation, Double> e : attributes.entrySet()) {
            if (e.getKey().getPath().equals(path)) return e.getValue();
        }
        return fallback;
    }

    /** 按技能参数 key 取值；未配置返回 fallback。 */
    public double combatParam(String key, double fallback) {
        return combat.paramOr(key, fallback);
    }

    /** 规范化后的形态序号（至少 1）。 */
    public int safeIndex() {
        return Math.max(1, index);
    }

    public static Builder builder(int index) {
        return new Builder(index);
    }

    public static final class Builder {
        private final int index;
        private final Map<ResourceLocation, Double> attributes = new java.util.LinkedHashMap<>();
        private CombatSpec combat = CombatSpec.EMPTY;
        private PhaseTrigger advance;
        private PhaseTrigger regress;

        private Builder(int index) {
            this.index = index;
        }

        public Builder attr(String namespace, String path, double value) {
            this.attributes.put(new ResourceLocation(namespace, path), value);
            return this;
        }

        public Builder attr(ResourceLocation id, double value) {
            this.attributes.put(id, value);
            return this;
        }

        public Builder combat(CombatSpec combat) {
            if (combat != null) this.combat = combat;
            return this;
        }

        public Builder advance(PhaseTrigger trigger) {
            this.advance = trigger;
            return this;
        }

        public Builder regress(PhaseTrigger trigger) {
            this.regress = trigger;
            return this;
        }

        public PhaseSpec build() {
            return new PhaseSpec(index, Map.copyOf(attributes), combat,
                Optional.ofNullable(advance), Optional.ofNullable(regress));
        }
    }
}
