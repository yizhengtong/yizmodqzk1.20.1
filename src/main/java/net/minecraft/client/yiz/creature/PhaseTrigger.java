package net.minecraft.client.yiz.creature;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.StringRepresentable;

/**
 * 形态切换触发条件。
 *
 * <p>{@code holdTicks} 表示条件需要持续满足多少 tick 才算成立（0 = 瞬时）；用于回退缓冲，
 * 避免血量在阈值附近抖动导致形态来回跳。</p>
 *
 * @param type      条件类型
 * @param value     阈值（血量比例用 0~1，tick 数用绝对值）
 * @param holdTicks 需要持续满足的 tick 数
 */
public record PhaseTrigger(Type type, double value, int holdTicks) {

    public enum Type implements StringRepresentable {
        /** 当前血量比例低于阈值。 */
        HEALTH_PERCENT_BELOW("health_percent_below"),
        /** 当前血量比例高于阈值。 */
        HEALTH_PERCENT_ABOVE("health_percent_above"),
        /** 当前形态已持续超过指定 tick。 */
        TICKS_IN_PHASE_ABOVE("ticks_in_phase_above"),
        /** 进入战斗累计超过指定 tick。 */
        COMBAT_TICKS_ABOVE("combat_ticks_above");

        public static final Codec<Type> CODEC = StringRepresentable.fromEnum(Type::values);

        private final String name;

        Type(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }
    }

    public static final Codec<PhaseTrigger> CODEC = RecordCodecBuilder.create(inst -> inst.group(
        Type.CODEC.fieldOf("type").forGetter(PhaseTrigger::type),
        Codec.DOUBLE.fieldOf("value").forGetter(PhaseTrigger::value),
        Codec.INT.optionalFieldOf("hold_ticks", 0).forGetter(PhaseTrigger::holdTicks)
    ).apply(inst, PhaseTrigger::new));

    public static PhaseTrigger healthBelow(double ratio, int holdTicks) {
        return new PhaseTrigger(Type.HEALTH_PERCENT_BELOW, ratio, holdTicks);
    }

    public static PhaseTrigger healthAbove(double ratio, int holdTicks) {
        return new PhaseTrigger(Type.HEALTH_PERCENT_ABOVE, ratio, holdTicks);
    }

    public static PhaseTrigger ticksInPhaseAbove(int ticks) {
        return new PhaseTrigger(Type.TICKS_IN_PHASE_ABOVE, ticks, 0);
    }

    public static PhaseTrigger combatTicksAbove(int ticks) {
        return new PhaseTrigger(Type.COMBAT_TICKS_ABOVE, ticks, 0);
    }

    /**
     * 判定条件是否成立。
     *
     * @param healthRatio   当前血量比例（0~1）
     * @param ticksInPhase  当前形态已持续 tick
     * @param combatTicks   进入战斗累计 tick
     */
    public boolean test(double healthRatio, int ticksInPhase, int combatTicks) {
        return switch (type) {
            case HEALTH_PERCENT_BELOW -> healthRatio < value;
            case HEALTH_PERCENT_ABOVE -> healthRatio > value;
            case TICKS_IN_PHASE_ABOVE -> ticksInPhase > value;
            case COMBAT_TICKS_ABOVE -> combatTicks > value;
        };
    }
}
