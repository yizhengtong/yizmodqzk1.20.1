package net.minecraft.client.yiz.api;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

/**
 * 物品描边标记 — 用 NBT 字段 {@code yizmodqzk:outline} 存描边等级（0-5）。
 *
 * <p>{@code /yiz mb <0-5>} 给主手物品写等级；{@code ItemRendererStarMixin} 渲染时读等级，
 * 非 -1 即描边（复用 8 方向描边 + 6 色 preset）。物品级 NBT 自动同步服务端→客户端，天然持久。</p>
 */
public final class OutlineMarker {

    private static final String KEY = "yizmodqzk:outline";

    private OutlineMarker() {}

    /** 读描边等级；无描边返回 -1。 */
    public static int getLevel(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(KEY, Tag.TAG_INT)) return -1;
        return tag.getInt(KEY);
    }

    /** 写描边等级（0-5）。 */
    public static void setLevel(ItemStack stack, int level) {
        stack.getOrCreateTag().putInt(KEY, level);
    }

    /** 移除描边标记。 */
    public static void clear(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag != null) tag.remove(KEY);
    }
}
