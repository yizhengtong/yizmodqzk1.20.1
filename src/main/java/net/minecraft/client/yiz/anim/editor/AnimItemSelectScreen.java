package net.minecraft.client.yiz.anim.editor;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.yiz.anim.AnimConfig;
import net.minecraft.client.yiz.anim.AnimLibrary;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 按 K 打开的物品选择界面：左栏是还没编辑过动画的物品，右栏是已经在配置表里的物品。
 * 点击任意物品进入第三人称编辑（编辑器里玩家模型会手持该物品）。
 *
 * <p>左栏取玩家背包里的物品（去重），右栏取 {@code animation_config.json} 的 {@code items} 键。</p>
 */
public class AnimItemSelectScreen extends Screen {

    private static final int ROW_H = 22;
    private static final int TOP = 46;
    private static final int BOTTOM = 42;
    private static final int MENU_W = 140;
    private static final int MENU_H = 38;

    private final List<ItemStack> unedited = new ArrayList<>();
    private final List<ItemStack> edited = new ArrayList<>();
    private int leftScroll;
    private int rightScroll;

    // 右键菜单（可扩展更多操作）
    private boolean menuOpen;
    private int menuX;
    private int menuY;
    private String menuItemId;
    private String menuItemName;

    public AnimItemSelectScreen() {
        super(Component.literal("Yiz 动画编辑器"));
        this.refreshItems();
    }

    private void refreshItems() {
        this.unedited.clear();
        this.edited.clear();

        // 左栏：玩家背包里未配置动画的物品
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            Map<String, ItemStack> unique = new LinkedHashMap<>();
            for (int i = 0; i < mc.player.getInventory().getContainerSize(); i++) {
                ItemStack stack = mc.player.getInventory().getItem(i);
                if (stack.isEmpty()) {
                    continue;
                }
                ResourceLocation key = ForgeRegistries.ITEMS.getKey(stack.getItem());
                if (key == null || AnimConfig.items().containsKey(key.toString())) {
                    continue;
                }
                unique.putIfAbsent(key.toString(), stack.copy());
            }
            this.unedited.addAll(unique.values());
        }

        // 右栏：配置表里已经编辑过的物品
        for (String id : AnimConfig.items().keySet()) {
            try {
                Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(id));
                if (item != null) {
                    this.edited.add(new ItemStack(item));
                }
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    protected void init() {
        this.addRenderableWidget(Button.builder(Component.literal("关闭"), b -> this.onClose())
                .bounds(this.width - 50, this.height - 28, 42, 20).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);
        int half = this.width / 2;
        graphics.fill(4, 4, half - 4, this.height - 8, 0xFF161616);
        graphics.fill(half + 4, 4, this.width - 4, this.height - 8, 0xFF1A1A1A);

        graphics.drawString(this.font, "未编辑物品", 12, 10, 0xFFFFCC66, false);
        graphics.drawString(this.font, "（点击物品 → 第三人称编辑）", 12, 22, 0xFF888888, false);
        graphics.drawString(this.font, "已编辑物品", half + 12, 10, 0xFF66CCFF, false);
        graphics.drawString(this.font, "（配置表 animation_config.json）", half + 12, 22, 0xFF888888, false);

        this.renderColumn(graphics, this.unedited, 8, half - 8, this.leftScroll, mouseX, mouseY, 4);
        this.renderColumn(graphics, this.edited, half + 8, this.width - 8, this.rightScroll, mouseX, mouseY, half + 4);

        graphics.drawString(this.font, "Esc 关闭  |  滚轮滚动  |  左键编辑  |  右键菜单",
                8, this.height - 16, 0xFF999999, false);
        super.render(graphics, mouseX, mouseY, partialTick);
        if (this.menuOpen) {
            this.renderContextMenu(graphics, mouseX, mouseY);
        }
    }

    /** 右键菜单：目前只有删除，后续可以在这里继续加操作项。 */
    private void renderContextMenu(GuiGraphics graphics, int mouseX, int mouseY) {
        int x = this.menuX();
        int y = this.menuY();
        graphics.fill(x, y, x + MENU_W, y + MENU_H, 0xF0181818);
        graphics.renderOutline(x, y, MENU_W, MENU_H, 0xFF777777);
        String title = this.menuItemName == null ? "" : this.menuItemName;
        if (this.font.width(title) > MENU_W - 8) {
            title = this.font.plainSubstrByWidth(title, MENU_W - 14) + "...";
        }
        graphics.drawString(this.font, title, x + 4, y + 4, 0xFFAAAAAA, false);
        boolean hover = mouseX >= x + 2 && mouseX <= x + MENU_W - 2
                && mouseY >= y + 16 && mouseY <= y + MENU_H - 2;
        graphics.fill(x + 2, y + 16, x + MENU_W - 2, y + MENU_H - 2,
                hover ? 0x88AA2222 : 0x44000000);
        graphics.drawString(this.font, "删除物品配置", x + 6, y + 18, 0xFFFFDDDD, false);
    }

    private void renderColumn(GuiGraphics graphics, List<ItemStack> items, int x0, int x1,
                              int scroll, int mouseX, int mouseY, int hoverEdge) {
        int rows = (this.height - BOTTOM - TOP) / ROW_H;
        if (items.isEmpty()) {
            graphics.drawString(this.font, "（空）", x0 + 8, TOP + 8, 0xFF666666, false);
            return;
        }
        for (int row = 0; row < rows; row++) {
            int index = scroll + row;
            if (index >= items.size()) {
                break;
            }
            ItemStack stack = items.get(index);
            int y = TOP + row * ROW_H;
            boolean hover = mouseX >= x0 && mouseX <= x1 && mouseY >= y && mouseY < y + ROW_H;
            if (hover) {
                graphics.fill(x0, y, x1, y + ROW_H - 2, 0x33FFFFFF);
            }
            graphics.renderItem(stack, x0 + 4, y + 3);
            String name = stack.getHoverName().getString();
            String id = ForgeRegistries.ITEMS.getKey(stack.getItem()) + "";
            graphics.drawString(this.font, name, x0 + 26, y + 3, 0xFFEEEEEE, false);
            graphics.drawString(this.font, id, x0 + 26, y + 12, 0xFF777777, false);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (this.menuOpen) {
            int x = this.menuX();
            int y = this.menuY();
            boolean inside = mouseX >= x && mouseX <= x + MENU_W && mouseY >= y && mouseY <= y + MENU_H;
            if (inside && mouseY >= y + 16) {
                this.deleteMenuItem();
            }
            this.menuOpen = false;
            return true;
        }
        int rows = (this.height - BOTTOM - TOP) / ROW_H;
        if (button == 1 && mouseY >= TOP && mouseY < this.height - BOTTOM) {
            // 右键已编辑物品：展开操作菜单（目前是删除，后续可在这里加更多项）
            int half = this.width / 2;
            int row = (int) ((mouseY - TOP) / ROW_H);
            if (mouseX >= half) {
                int index = this.rightScroll + row;
                if (index >= 0 && index < this.edited.size()) {
                    ItemStack stack = this.edited.get(index);
                    ResourceLocation key = ForgeRegistries.ITEMS.getKey(stack.getItem());
                    if (key != null) {
                        this.menuOpen = true;
                        this.menuX = (int) mouseX;
                        this.menuY = (int) mouseY;
                        this.menuItemId = key.toString();
                        this.menuItemName = stack.getHoverName().getString();
                    }
                    return true;
                }
            }
        }
        if (button == 0 && mouseY >= TOP && mouseY < this.height - BOTTOM) {
            int half = this.width / 2;
            int row = (int) ((mouseY - TOP) / ROW_H);
            if (mouseX < half) {
                int index = this.leftScroll + row;
                if (index >= 0 && index < this.unedited.size()) {
                    this.openEditor(this.unedited.get(index));
                    return true;
                }
            } else {
                int index = this.rightScroll + row;
                if (index >= 0 && index < this.edited.size()) {
                    this.openEditor(this.edited.get(index));
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void deleteMenuItem() {
        if (this.menuItemId == null) {
            return;
        }
        AnimConfig.removeItem(this.menuItemId);
        AnimConfig.save();
        AnimLibrary.reload();
        this.refreshItems();
        int rows = (this.height - BOTTOM - TOP) / ROW_H;
        this.rightScroll = clamp(this.rightScroll, this.edited.size(), rows);
        if (this.minecraft != null && this.minecraft.player != null) {
            this.minecraft.player.displayClientMessage(
                    Component.literal("[动画] 已删除物品配置 " + this.menuItemId), false);
        }
    }

    private int menuX() {
        return Math.max(4, Math.min(this.menuX, this.width - MENU_W - 4));
    }

    private int menuY() {
        return Math.max(4, Math.min(this.menuY, this.height - MENU_H - 4));
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (mouseY >= TOP && mouseY < this.height - BOTTOM) {
            int rows = (this.height - BOTTOM - TOP) / ROW_H;
            if (mouseX < this.width / 2) {
                this.leftScroll = clamp(this.leftScroll - (int) Math.signum(delta), this.unedited.size(), rows);
            } else {
                this.rightScroll = clamp(this.rightScroll - (int) Math.signum(delta), this.edited.size(), rows);
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    private static int clamp(int scroll, int size, int rows) {
        return Math.max(0, Math.min(scroll, Math.max(0, size - rows)));
    }

    private void openEditor(ItemStack stack) {
        Minecraft.getInstance().setScreen(new AnimEditorScreen(stack.copy()));
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(null);
    }

    @Override
    public boolean isPauseScreen() {
        return true;
    }
}
