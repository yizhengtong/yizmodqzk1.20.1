package net.minecraft.client.yiz.creature;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 生物技能注册表。
 *
 * <p>把技能实现与 id 绑定，使 {@link CombatSpec#skillId()} 从「配置里的死字符串」变成可派发的引用。
 * 实体侧只需要在合适的时机调用 {@link #select} / {@link #tickActive}，不必知道具体是哪个技能。</p>
 */
public final class CreatureSkills {

    private static final Map<ResourceLocation, CreatureSkill> REGISTRY = new ConcurrentHashMap<>();

    private CreatureSkills() {}

    public static void register(CreatureSkill skill) {
        if (skill == null || skill.id() == null) return;
        REGISTRY.put(skill.id(), skill);
    }

    public static CreatureSkill byId(ResourceLocation id) {
        return id == null ? null : REGISTRY.get(id);
    }

    /** 按 id 字符串取技能（容错：不带命名空间时按 yizmodqzk 补全）。 */
    public static CreatureSkill byId(String id) {
        if (id == null || id.isEmpty()) return null;
        ResourceLocation rl = id.indexOf(':') >= 0
            ? ResourceLocation.tryParse(id)
            : new ResourceLocation("yizmodqzk", id);
        return byId(rl);
    }

    /**
     * 从战斗参数里取要施放的技能；未配置或未注册返回 null。
     */
    public static CreatureSkill fromSpec(CombatSpec spec) {
        if (spec == null || spec.skillId().isEmpty()) return null;
        return byId(spec.skillId().get());
    }
}
