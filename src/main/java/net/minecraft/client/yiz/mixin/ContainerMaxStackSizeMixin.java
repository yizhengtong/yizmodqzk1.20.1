package net.minecraft.client.yiz.mixin;

import net.minecraft.client.yiz.core.ItemStackSizeOverride;
import net.minecraft.world.Container;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

/**
 * {@link Container#getMaxStackSize()} 覆盖 — 放宽容器/背包格子物理上限到 {@value ItemStackSizeOverride#MAX}。
 *
 * <p>原版 {@code Container.getMaxStackSize()} 默认返回 64，所有实现类（SimpleContainer/Inventory 等）
 * 都继承这个值，导致「放入箱子 99 变 64」「拾取合并只能到 64」等不一致。覆盖为 99 后，
 * 容器格子和背包格子都不再被 64 截断；未覆盖的物品（默认 64）仍受自身物品上限约束，不会超堆。</p>
 */
@Mixin(Container.class)
public interface ContainerMaxStackSizeMixin {

    /**
     * @reason 原版容器/背包格子的物理上限固定 64，导致被堆叠核心改成 99 的物品放入箱子、
     *         拾取合并时都被截断到 64。覆盖为 {@value ItemStackSizeOverride#MAX} 放宽上限，
     *         未覆盖的物品仍受自身物品上限约束不会超堆。
     * @author yiz
     */
    @Overwrite
    default int getMaxStackSize() {
        return ItemStackSizeOverride.MAX;
    }
}
