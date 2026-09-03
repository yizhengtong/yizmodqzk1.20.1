package net.minecraft.client.yiz.itemcfg.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.yiz.itemcfg.client.ItemConfigClientHandler;
import net.minecraft.client.yiz.itemcfg.network.C2SItemConfigTogglePayload;
import net.minecraft.client.yiz.itemcfg.network.S2CItemConfigStatePayload;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 万能物品配置 GUI（普通 Screen，非容器）。
 *
 * <p>根据服务端下发的功能清单自动生成开关行：中文名 + 右侧开关矩形，
 * 手写滚动/矩形命中（仿 AttributeEditorScreen），中文用 font.drawString。</p>
 */
public class ItemConfigScreen extends Screen {

    private static final int PANEL_W = 220;
    private static final int ROW_H = 20;
    private static final int VISIBLE_ROWS = 12;
    private static final int SWITCH_W = 24;
    private static final int SWITCH_H = 10;

    private final String itemId;
    private final String nameKey;
    private final String modName;
    private final List<FeatureRow> rows = new ArrayList<>();
    private int scrollOffset = 0;
    private boolean awaitingServer = false;

    public ItemConfigScreen(ItemStack held, S2CItemConfigStatePayload state) {
        super(Component.literal("ItemConfig"));
        this.itemId = state.itemId;
        this.nameKey = state.nameKey;
        this.modName = state.modName;
        applyState(state);
    }

    public boolean matches(String itemId) {
        return this.itemId.equals(itemId);
    }

    /** 服务端回执后刷新真实状态。 */
    public void applyState(S2CItemConfigStatePayload state) {
        rows.clear();
        for (var e : state.entries) {
            rows.add(new FeatureRow(e.feature(), e.zh(), e.disabled(), e.declaredBy()));
        }
        awaitingServer = false;
    }

    private int panelHeight() {
        return 40 + Math.min(rows.size(), VISIBLE_ROWS) * ROW_H + 20;
    }

    private int panelX() {
        return (this.width - PANEL_W) / 2;
    }

    private int panelY() {
        return (this.height - panelHeight()) / 2;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        int px = panelX();
        int py = panelY();
        int panelH = panelHeight();

        // 面板背景
        graphics.fill(px - 4, py - 4, px + PANEL_W + 4, py + panelH + 4, 0xE0000000);
        graphics.fill(px, py, px + PANEL_W, py + panelH, 0xFF333333);

        // 标题：物品名（本地化）+ mod 名
        graphics.drawString(this.font, Component.translatable(nameKey), px + 8, py + 8, 0xFFFFFFFF);
        graphics.drawString(this.font, Component.literal(modName), px + 8, py + 22, 0xFFAAAAAA);

        // 功能行
        int maxOffset = Math.max(0, rows.size() - VISIBLE_ROWS);
        if (scrollOffset > maxOffset) scrollOffset = maxOffset;
        int listY = py + 40;
        for (int i = 0; i < VISIBLE_ROWS; i++) {
            int idx = scrollOffset + i;
            if (idx >= rows.size()) break;
            FeatureRow row = rows.get(idx);
            int ry = listY + i * ROW_H;

            // 行悬停高亮
            if (mouseX >= px && mouseX < px + PANEL_W && mouseY >= ry && mouseY < ry + ROW_H) {
                graphics.fill(px, ry, px + PANEL_W, ry + ROW_H, 0x44FFFFFF);
            }

            // 功能中文名
            graphics.drawString(this.font, row.zh(), px + 8, ry + 5, 0xFFFFFFFF);
            // 语义功能标注（adapter 声明）
            if (row.declaredBy() == 1) {
                graphics.drawString(this.font, "[适配]", px + PANEL_W - 96, ry + 5, 0xFF88AAFF);
            } else if (row.declaredBy() == 2) {
                // 通用持有/背包效果（适配库驱动）
                graphics.drawString(this.font, "[持有]", px + PANEL_W - 96, ry + 5, 0xFFAAFF88);
            }

            // 开关矩形
            int sx = px + PANEL_W - SWITCH_W - 8;
            int sy = ry + (ROW_H - SWITCH_H) / 2;
            graphics.fill(sx, sy, sx + SWITCH_W, sy + SWITCH_H,
                    awaitingServer ? 0xFF555555 : (row.disabled() ? 0xFF66CC66 : 0xFF666666));
            graphics.drawString(this.font,
                    Component.literal(row.disabled() ? "关" : "开"),
                    sx + (SWITCH_W - 8) / 2, sy - 1, 0xFF000000);
        }

        // 滚动条指示
        if (maxOffset > 0) {
            int barH = Math.max(6, (panelH - 60) * VISIBLE_ROWS / rows.size());
            int barY = listY + (scrollOffset * ((panelH - 60) - barH) / maxOffset);
            graphics.fill(px + PANEL_W - 3, barY, px + PANEL_W - 1, barY + barH, 0x88AAAAAA);
        }

        // 底部提示
        graphics.drawString(this.font, Component.literal("Shift+U 关闭 | ESC 返回"), px + 8, py + panelH - 12, 0xFF888888);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0 || awaitingServer) return super.mouseClicked(mx, my, button);
        int px = panelX();
        int listY = panelY() + 40;
        for (int i = 0; i < VISIBLE_ROWS; i++) {
            int idx = scrollOffset + i;
            if (idx >= rows.size()) break;
            FeatureRow row = rows.get(idx);
            int ry = listY + i * ROW_H;
            int sx = px + PANEL_W - SWITCH_W - 8;
            int sy = ry + (ROW_H - SWITCH_H) / 2;
            if (mx >= sx && mx < sx + SWITCH_W && my >= sy && my < sy + SWITCH_H) {
                awaitingServer = true;  // 防连点
                C2SItemConfigTogglePayload.send(itemId, row.feature(), !row.disabled());
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        int maxOffset = Math.max(0, rows.size() - VISIBLE_ROWS);
        scrollOffset = net.minecraft.util.Mth.clamp(scrollOffset + (delta > 0 ? -1 : 1), 0, maxOffset);
        return true;
    }

    @Override
    public void onClose() {
        super.onClose();
        ItemConfigClientHandler.onClosed();
    }
}
