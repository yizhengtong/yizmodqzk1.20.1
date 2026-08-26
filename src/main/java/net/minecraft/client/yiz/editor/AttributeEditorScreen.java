package net.minecraft.client.yiz.editor;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.yiz.tool.icon.AttributeIconRegistry;
import net.minecraft.client.yiz.tool.icon.IconBlitHelper;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 属性编辑台客户端 Screen（阶段 C：全功能交互）。
 *
 * <p>GUI 背景 220×224（gui/attribute_editor.png）。含：</p>
 * <ul>
 *   <li>② EditBox 数字输入 (30,106) 36×10</li>
 *   <li>③ 属性列表 4 行，可点击/可滚动</li>
 *   <li>④ 竖直滑块 拖动切换显示行</li>
 *   <li>⑤ HUD（阶段 D 补）</li>
 * </ul>
 */
public class AttributeEditorScreen extends AbstractContainerScreen<AttributeEditorMenu> {

    private static final ResourceLocation GUI_TEXTURE =
        ResourceLocation.fromNamespaceAndPath("yizmodqzk", "textures/gui/attribute_editor.png");

    public static final int GUI_WIDTH = 220;
    public static final int GUI_HEIGHT = 224;

    // ── ③ 属性列表几何 ───────────────────────────────────────
    private static final int LIST_X = 29, LIST_Y0 = 28;   // 第一行左上角
    private static final int LIST_ROW_H = 18;              // 行高（19px间距 → 留1px间隔）
    private static final int LIST_ROW_GAP = 19;            // 行间距
    private static final int LIST_W = 88;                  // 单行宽
    private static final int LIST_ROWS = 4;                // 可见行数
    private static final int LIST_TEXT_X = 32;             // 文字起始 x（留 3px padding）
    private static final int LIST_TEXT_Y = 34;             // 文字起始 y（对齐）

    // ── ④ 滑块几何 ───────────────────────────────────────────
    private static final int SLIDER_TRACK_X = 123, SLIDER_TRACK_Y = 37;
    private static final int SLIDER_TRACK_W = 8, SLIDER_TRACK_H = 60;
    private static final int SLIDER_BUTTON_W = 6, SLIDER_BUTTON_H = 14;
    private static final int SLIDER_BUTTON_X = 124;
    private static final int SLIDER_MIN_Y = 38;
    private static final int SLIDER_MAX_Y = 83;            // = 97 - 14

    // ── ② EditBox 几何 ───────────────────────────────────────
    private static final int EDIT_X = 30, EDIT_Y = 106;
    private static final int EDIT_W = 36, EDIT_H = 10;

    // ── ⑤ HUD 几何 ───────────────────────────────────────────
    private static final int HUD_X = 147, HUD_Y = 33;
    private static final int HUD_W = 59, HUD_H = 67;
    private static final int HUD_LINE_H = 9;   // 每行高度
    private static final int HUD_MAX_LINES = 7; // 最多显示行数

    // ── 状态 ──────────────────────────────────────────────────
    private EditBox valueInput;
    private int scrollOffset = 0;      // ③ 列表顶部属性索引
    private boolean draggingSlider = false;
    private int hoveredRow = -1;       // 鼠标悬停的行 (-1 = none)
    private int hudScrollOffset = 0;   // ⑤ HUD 滚动偏移
    private record HudRow(String label, String attrId) {}
    private List<HudRow> hudLines = List.of(); // ⑤ 当前帧 HUD 行（带 attrId 供图标查询）

    public AttributeEditorScreen(AttributeEditorMenu menu, Inventory playerInv, Component title) {
        super(menu, playerInv, title);
        this.imageWidth = GUI_WIDTH;
        this.imageHeight = GUI_HEIGHT;
    }

    // ── 初始化 ────────────────────────────────────────────────

    @Override
    protected void init() {
        super.init();

        // ② EditBox（数字输入）
        this.valueInput = new EditBox(
            this.font,
            this.leftPos + EDIT_X,
            this.topPos + EDIT_Y,
            EDIT_W, EDIT_H,
            Component.empty()
        );
        this.valueInput.setValue("0");
        this.valueInput.setFilter(s -> s.matches("[-]?[0-9]*\\.?[0-9]*"));
        this.valueInput.setMaxLength(10);
        this.addRenderableWidget(this.valueInput);
    }

    // ── 背景 ──────────────────────────────────────────────────

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        // 背景图向左上偏移 1 像素对齐实际槽位
        graphics.blit(GUI_TEXTURE, leftPos - 1, topPos - 1, 0f, 0f, imageWidth, imageHeight, imageWidth, imageHeight);
        renderAttributeList(graphics, mouseX, mouseY);
        renderSlider(graphics);
        renderHud(graphics, mouseX, mouseY);
    }

    // ── ③ 属性列表渲染 ───────────────────────────────────────

    private void renderAttributeList(GuiGraphics graphics, int mouseX, int mouseY) {
        var all = EditableAttribute.getAll();
        int total = all.size();
        int maxOffset = Math.max(0, total - LIST_ROWS);
        if (scrollOffset > maxOffset) scrollOffset = maxOffset;
        if (scrollOffset < 0) scrollOffset = 0;

        ItemStack placed = getPlacedItem();

        for (int i = 0; i < LIST_ROWS; i++) {
            int idx = scrollOffset + i;
            if (idx >= total) break;

            EditableAttribute attr = all.get(idx);
            double currentVal = placed.isEmpty() ? 0 : attr.getter().apply(placed);

            int rowX = leftPos + LIST_X;
            int rowY = topPos + LIST_Y0 + i * LIST_ROW_GAP;

            // 悬停高亮
            boolean hovered = isMouseInListRow(mouseX, mouseY, i);
            if (hovered) {
                graphics.fill(rowX, rowY, rowX + LIST_W, rowY + LIST_ROW_H, 0x44FFFFFF);
                this.hoveredRow = i;
            }

            // 图标 + 文字
            String label = attr.listLabel(currentVal);
            int textColor = attr.unimplemented() ? 0xFFAAAAAA : 0xFFFFFFFF;
            int textX = leftPos + LIST_TEXT_X;
            var icon = AttributeIconRegistry.get(attr.id());
            if (icon != null) {
                int sz = AttributeIconRegistry.iconPx();  // 16
                IconBlitHelper.blit(graphics, icon, textX, rowY + (LIST_ROW_H - sz) / 2, sz);
                textX += sz + 2;
            }
            graphics.drawString(this.font, label, textX, rowY + 5, textColor);
        }
    }

    private boolean isMouseInListRow(int mx, int my, int row) {
        int rx = leftPos + LIST_X;
        int ry = topPos + LIST_Y0 + row * LIST_ROW_GAP;
        return mx >= rx && mx < rx + LIST_W && my >= ry && my < ry + LIST_ROW_H;
    }

    // ── ④ 滑块渲染 ───────────────────────────────────────────

    private void renderSlider(GuiGraphics graphics) {
        // 轨道
        int tx = leftPos + SLIDER_TRACK_X;
        int ty = topPos + SLIDER_TRACK_Y;
        graphics.fill(tx, ty, tx + SLIDER_TRACK_W, ty + SLIDER_TRACK_H, 0xFF555555);

        // 按钮
        int total = EditableAttribute.getAll().size();
        int maxOffset = Math.max(0, total - LIST_ROWS);
        float ratio = maxOffset == 0 ? 0f : (float) scrollOffset / maxOffset;
        int buttonY = SLIDER_MIN_Y + Math.round(ratio * (SLIDER_MAX_Y - SLIDER_MIN_Y));

        int bx = leftPos + SLIDER_BUTTON_X;
        int by = topPos + buttonY;
        graphics.fill(bx, by, bx + SLIDER_BUTTON_W, by + SLIDER_BUTTON_H,
            draggingSlider ? 0xFFFFFFFF : 0xFFAAAAAA);
    }

    private boolean isMouseOnSlider(int mx, int my) {
        int bx = leftPos + SLIDER_BUTTON_X;
        int total = EditableAttribute.getAll().size();
        int maxOffset = Math.max(0, total - LIST_ROWS);
        float ratio = maxOffset == 0 ? 0f : (float) scrollOffset / maxOffset;
        int by = topPos + SLIDER_MIN_Y + Math.round(ratio * (SLIDER_MAX_Y - SLIDER_MIN_Y));
        return mx >= bx && mx < bx + SLIDER_BUTTON_W && my >= by && my < by + SLIDER_BUTTON_H;
    }

    private boolean isMouseOnSliderTrack(int mx, int my) {
        int tx = leftPos + SLIDER_TRACK_X;
        int ty = topPos + SLIDER_TRACK_Y;
        return mx >= tx && mx < tx + SLIDER_TRACK_W && my >= ty && my < ty + SLIDER_TRACK_H;
    }

    // ── ⑤ HUD 渲染 ───────────────────────────────────────────

    private void renderHud(GuiGraphics graphics, int mouseX, int mouseY) {
        Player player = Minecraft.getInstance().player;
        if (player == null) return;

        // 每帧重建 HUD 行（取所有非零玩家属性）
        var all = EditableAttribute.getAll();
        hudLines = new ArrayList<>();
        for (EditableAttribute attr : all) {
            double v = attr.playerReader().apply(player);
            String label = attr.hudLabel(v);
            if (label != null) hudLines.add(new HudRow(label, attr.id()));
        }

        int maxScroll = Math.max(0, hudLines.size() - HUD_MAX_LINES);
        if (hudScrollOffset > maxScroll) hudScrollOffset = maxScroll;
        if (hudScrollOffset < 0) hudScrollOffset = 0;

        // 裁剪区域绘制
        int hx = leftPos + HUD_X;
        int hy = topPos + HUD_Y;
        int end = Math.min(hudLines.size(), hudScrollOffset + HUD_MAX_LINES);
        for (int i = hudScrollOffset; i < end; i++) {
            int lineY = hy + (i - hudScrollOffset) * HUD_LINE_H;
            if (lineY + HUD_LINE_H > hy + HUD_H) break;
            int tx = hx;
            HudRow row = hudLines.get(i);
            var icon = AttributeIconRegistry.get(row.attrId());
            if (icon != null) {
                int sz = HUD_LINE_H;  // 9，与行高一致（HUD 预览区紧凑）
                IconBlitHelper.blit(graphics, icon, tx, lineY, sz);
                tx += sz + 1;
            }
            graphics.drawString(this.font, row.label(), tx, lineY, 0xFFFFFFFF);
        }

        // 有可滚动行时画小滚动条指示
        if (maxScroll > 0) {
            int barH = Math.max(4, HUD_H * HUD_MAX_LINES / hudLines.size());
            int barY = hy + (hudScrollOffset * (HUD_H - barH) / maxScroll);
            graphics.fill(hx + HUD_W - 2, barY, hx + HUD_W, barY + barH, 0x88AAAAAA);
        }
    }

    private boolean isMouseInHud(int mx, int my) {
        int hx = leftPos + HUD_X;
        int hy = topPos + HUD_Y;
        return mx >= hx && mx < hx + HUD_W && my >= hy && my < hy + HUD_H;
    }

    // ── 辅助 ──────────────────────────────────────────────────

    private ItemStack getPlacedItem() {
        return this.menu.slots.get(0).getItem();
    }

    private double getInputValue() {
        try {
            return Double.parseDouble(this.valueInput.getValue());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ── 鼠标事件 ──────────────────────────────────────────────

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        // 点击属性列表行 → 应用属性
        if (button == 0) {
            for (int i = 0; i < LIST_ROWS; i++) {
                if (isMouseInListRow((int) mx, (int) my, i)) {
                    int idx = scrollOffset + i;
                    if (idx >= 0 && idx < EditableAttribute.getAll().size()) {
                        applyAttribute(idx);
                        return true;
                    }
                }
            }
            // 点击滑块 → 开始拖拽
            if (isMouseOnSlider((int) mx, (int) my)) {
                draggingSlider = true;
                return true;
            }
            if (isMouseOnSliderTrack((int) mx, (int) my)) {
                draggingSlider = true;
                updateSliderFromMouse((int) my);
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        draggingSlider = false;
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dragX, double dragY) {
        if (draggingSlider) {
            updateSliderFromMouse((int) my);
            return true;
        }
        return super.mouseDragged(mx, my, button, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        // 在 HUD 区域滚动 → 调整 HUD offset
        if (isMouseInHud((int) mx, (int) my)) {
            int maxScroll = Math.max(0, hudLines.size() - HUD_MAX_LINES);
            hudScrollOffset = net.minecraft.util.Mth.clamp(hudScrollOffset + (delta > 0 ? -1 : 1), 0, maxScroll);
            return true;
        }
        // 在属性列表区域滚动 → 调整列表 offset
        boolean inList = false;
        for (int i = 0; i < LIST_ROWS; i++) {
            if (isMouseInListRow((int) mx, (int) my, i)) { inList = true; break; }
        }
        if (inList || (mx >= leftPos + LIST_X && mx < leftPos + LIST_X + LIST_W
            && my >= leftPos + LIST_Y0 && my < leftPos + LIST_Y0 + LIST_ROWS * LIST_ROW_GAP)) {
            int total = EditableAttribute.getAll().size();
            int maxOffset = Math.max(0, total - LIST_ROWS);
            scrollOffset = net.minecraft.util.Mth.clamp(scrollOffset + (delta > 0 ? -1 : 1), 0, maxOffset);
            return true;
        }
        return super.mouseScrolled(mx, my, delta);
    }

    private void updateSliderFromMouse(int mouseY) {
        int relY = mouseY - topPos - SLIDER_MIN_Y;
        int range = SLIDER_MAX_Y - SLIDER_MIN_Y;
        relY = net.minecraft.util.Mth.clamp(relY, 0, range);
        float ratio = (float) relY / range;
        int total = EditableAttribute.getAll().size();
        int maxOffset = Math.max(0, total - LIST_ROWS);
        scrollOffset = Math.round(ratio * maxOffset);
    }

    private void applyAttribute(int attrIdx) {
        EditableAttribute attr = EditableAttribute.getAll().get(attrIdx);
        double value = getInputValue();
        C2SAttributeEditorPayload.send(attr.id(), value);
    }

    // ── 按键 ──────────────────────────────────────────────────

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (this.valueInput.keyPressed(keyCode, scanCode, modifiers)) return true;
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (this.valueInput.charTyped(codePoint, modifiers)) return true;
        return super.charTyped(codePoint, modifiers);
    }

    // ── 上层渲染 ──────────────────────────────────────────────

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        this.renderTooltip(graphics, mouseX, mouseY);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        // 不绘制 label 文字（GUI 背景图已包含标题）
    }
}
