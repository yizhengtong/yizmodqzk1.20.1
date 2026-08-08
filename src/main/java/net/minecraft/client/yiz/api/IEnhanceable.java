package net.minecraft.client.yiz.api;

import net.minecraft.client.yiz.editor.EnhanceEntry;
import net.minecraft.client.yiz.editor.EnhanceTagRegistry;
import net.minecraft.client.yiz.editor.SkillConfigStorage;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 可强化物品接口。
 *
 * <p>主动技能和被动物品均可实现此接口，返回可强化属性列表与可用标签。
 * GUI 通过 {@link #getEnhanceEntries} 获取全部强化条目（最多 6 条）填充加强槽。</p>
 *
 * <h3>设计分层</h3>
 * <ul>
 *   <li>{@link #getEnhanceableAttributes} — A 类：数值属性（自动检测，90% 无需覆写）</li>
 *   <li>{@link #getProvidedTags} — 被动物品对外提供的触发标签池</li>
 *   <li>{@link #getEnhanceEntries} — 汇总：属性 + 标签 → GUI 6 格加强槽</li>
 * </ul>
 */
public interface IEnhanceable {

    /** 无需覆写：自动检测物品上所有非零的可编辑属性 */
    default List<EnhanceEntry.Attribute> getEnhanceableAttributes(ItemStack stack) {
        // TODO(1.20.1-port): 依赖 editor/EditableAttribute（1.21.1 可编辑属性定义，未移植）。
        // 原逻辑：遍历 EditableAttribute.getAll()，收集 |attr.getter().apply(stack)| > 0.001 的属性，
        // 构造 new EnhanceEntry.Attribute(attr)。待 EditableAttribute 移植后恢复本方法。
        return new ArrayList<>();
    }

    /**
     * 此物品对外提供的触发标签。
     * 被动物品覆写此方法，声明自己可为技能提供的标签。
     */
    default List<String> getProvidedTags(ItemStack stack) { return List.of(); }

    /**
     * 获取此物品可用作强化的全部条目（属性 + 标签）。
     * <p>优先级：{@code SkillEnhanceConfig} 预定义映射 > 自动检测属性 + 被动标签。</p>
     * <p>1.20.1 移植：SkillEnhanceConfig 未移植，配置分支暂跳过（见 TODO）。</p>
     */
    default List<EnhanceEntry> getEnhanceEntries(ItemStack stack, Player player) {
        // TODO(1.20.1-port): 依赖 editor/SkillEnhanceConfig（1.21.1 用户配置映射，未移植）。
        // 原逻辑：var configEntries = SkillEnhanceConfig.getEnhancementsFor(stack);
        //         if (!configEntries.isEmpty()) return configEntries;
        // —— 当前跳过配置分支，直接走「自动检测属性 + 被动标签」回退。
        List<EnhanceEntry> entries = new ArrayList<>();
        entries.addAll(getEnhanceableAttributes(stack));
        if (player != null) {
            var data = SkillConfigStorage.get(player.getUUID());
            if (data != null) {
                for (int i = 0; i < 3; i++) {
                    ItemStack passive = data.passiveLoad().getItem(i);
                    if (!passive.isEmpty() && passive.getItem() instanceof IEnhanceable pe) {
                        for (String tagKey : pe.getProvidedTags(passive)) {
                            String name = EnhanceTagRegistry.displayName(tagKey);
                            String desc = EnhanceTagRegistry.description(tagKey);
                            entries.add(new EnhanceEntry.Tag(tagKey, name, desc));
                        }
                    }
                }
            }
        }
        if (entries.size() > 6) entries = entries.subList(0, 6);
        return entries;
    }
}
