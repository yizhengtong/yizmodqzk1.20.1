package net.minecraft.client.yiz.editor;

import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * 属性编辑台服务端 Menu（1.20.1 移植版）。
 *
 * <p>槽位布局：slot 0 放置槽(9,28)；slot 1-27 玩家主背包 9×3，首格(28,142)；slot 28-36 快捷栏，首格(28,200)。</p>
 */
public class AttributeEditorMenu extends AbstractContainerMenu {

    /** 放置槽容器（引用 BlockEntity 的 items）。 */
    private final Container container;

    // ── 客户端构造（Client-only menu，由 MenuType 创建）──────
    public AttributeEditorMenu(int containerId, Inventory playerInv) {
        this(containerId, playerInv, new SimpleContainer(1));
    }

    // ── 服务端构造 ────────────────────────────────────────────
    public AttributeEditorMenu(int containerId, Inventory playerInv, Container placementContainer) {
        super(AttributeEditorRegistries.ATTRIBUTE_EDITOR_MENU.get(), containerId);
        this.container = placementContainer;

        this.addSlot(new Slot(placementContainer, 0, 9, 28));

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(playerInv, col + row * 9 + 9, 28 + col * 18, 142 + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(playerInv, col, 28 + col * 18, 200));
        }
    }

    // ── Shift 点击快速移动 ─────────────────────────────────────

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = this.slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;

        ItemStack src = slot.getItem();
        ItemStack copy = src.copy();

        if (index == 0) {
            if (!this.moveItemStackTo(src, 1, 37, true)) {
                return ItemStack.EMPTY;
            }
        } else {
            if (!this.moveItemStackTo(src, 0, 1, false)) {
                return ItemStack.EMPTY;
            }
        }

        if (src.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }

        slot.onTake(player, src);
        return copy;
    }

    // ── 标准覆写 ──────────────────────────────────────────────

    @Override
    public boolean stillValid(Player player) {
        return this.container.stillValid(player);
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        // 容器关闭时不掉落（物品保留在 BlockEntity 中）
    }

    /** 获取放置槽容器引用（供 Screen 使用）。 */
    public Container getContainer() {
        return container;
    }

    // ── 客户端 MenuType 构造工厂 ──────────────────────────────

    /** 创建客户端 Menu 的工厂方法（用于 MenuType 注册）。 */
    public static AttributeEditorMenu createClientMenu(int containerId, Inventory playerInv) {
        return new AttributeEditorMenu(containerId, playerInv);
    }
}
