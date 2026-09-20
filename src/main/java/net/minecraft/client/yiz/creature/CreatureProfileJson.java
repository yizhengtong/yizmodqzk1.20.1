package net.minecraft.client.yiz.creature;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 数据包 JSON 形态的生物原型定义。
 *
 * <p>目录约定 {@code data/<namespace>/yiz_creature/*.json}，文件名（相对路径）就是原型 id。
 * 解析后转成 {@link CreatureProfile} 走 {@link CreatureProfileRegistry#registerData}，
 * 与代码轨默认值共用同一套解析与应用路径。</p>
 *
 * <p>示例：</p>
 * <pre>{@code
 * {
 *   "entity_type": "yizxianmod:tiedoushi",
 *   "parent": "yizxianmod:melee_base",
 *   "attributes": { "yizmodqzk:armor": 12.0 },
 *   "effects": ["yizmodqzk:knockback_immunity"],
 *   "combat": { "attack_interval": 45, "attack_range": 3.5 }
 * }
 * }</pre>
 */
public record CreatureProfileJson(
    Optional<ResourceLocation> entityType,
    Optional<ResourceLocation> parent,
    Map<ResourceLocation, Double> attributes,
    List<String> effects,
    Optional<CombatSpec> combat,
    List<PhaseSpec> phases
) {

    public static final Codec<CreatureProfileJson> CODEC = RecordCodecBuilder.create(inst -> inst.group(
        ResourceLocation.CODEC.optionalFieldOf("entity_type").forGetter(CreatureProfileJson::entityType),
        ResourceLocation.CODEC.optionalFieldOf("parent").forGetter(CreatureProfileJson::parent),
        Codec.unboundedMap(ResourceLocation.CODEC, Codec.DOUBLE)
            .optionalFieldOf("attributes", Map.of()).forGetter(CreatureProfileJson::attributes),
        Codec.STRING.listOf().optionalFieldOf("effects", List.of()).forGetter(CreatureProfileJson::effects),
        CombatSpec.CODEC.optionalFieldOf("combat").forGetter(CreatureProfileJson::combat),
        PhaseSpec.LIST_CODEC.optionalFieldOf("phases", List.of()).forGetter(CreatureProfileJson::phases)
    ).apply(inst, CreatureProfileJson::new));

    /** 转成组件集合（属性 + 效果 + 战斗 + 形态）。 */
    public ComponentMap toComponents() {
        ComponentMap map = ComponentMap.EMPTY;
        if (!attributes.isEmpty()) map = map.with(CreatureComponents.ATTRIBUTES, attributes);
        if (combat.isPresent()) map = map.with(CreatureComponents.COMBAT, combat.get());
        if (!phases.isEmpty()) map = map.with(CreatureComponents.PHASES, phases);
        for (String id : effects) {
            ComponentType<Boolean> type = CreatureComponents.booleanEffect(id);
            if (type != null) map = map.with(type, Boolean.TRUE);
        }
        return map;
    }

    /** 转成原型（id 由文件名提供）。 */
    public CreatureProfile toProfile(ResourceLocation id) {
        return CreatureProfile.builder(id)
            .parent(parent.orElse(null))
            .components(toComponents())
            .build();
    }
}
