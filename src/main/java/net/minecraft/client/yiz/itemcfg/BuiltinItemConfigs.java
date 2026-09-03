package net.minecraft.client.yiz.itemcfg;

import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * 内置 vanilla 持有类物品功能（反射识别不了的引擎级被动效果）适配声明。
 *
 * <p>GUI 对所有物品显示通用"持有/背包效果"（HELD_EFFECTS）开关，关闭后由**适配库**
 * 驱动效果失效。适配库 = 本类声明哪些 vanilla 物品有引擎级持有效果 + 对应门控 mixin。</p>
 *
 * <p>当前：不死图腾免死由 {@code ItemCfgTotemGateMixin} 直接挂通用 HELD_EFFECTS 开关
 * （检查 Items.TOTEM_OF_UNDYING），无需本类声明。未来 vanilla 扩展（鞘翅滑翔/盾牌格挡）
 * 在此声明并在适配库 mixin 里挂 HELD_EFFECTS 拦截。</p>
 */
public final class BuiltinItemConfigs {

    private BuiltinItemConfigs() {}

    /** 返回物品的内置持有类功能声明（当前为空，不死图腾由通用 HELD_EFFECTS 驱动）。 */
    public static List<AdapterRegistry.AdapterFeature> featuresFor(ResourceLocation id) {
        return List.of();
    }
}
