package net.minecraft.client.yiz.tool.icon;

import net.minecraft.client.gui.GuiGraphics;

/**
 * 属性图标 blit 辅助 — 用 9-参 blit 从 sprite sheet 切出对应 32×32 格，缩放到指定尺寸绘制。
 *
 * <p>所有 Screen/HUD 的图标 blit 点统一走这里，避免 UV 计算散落各处。
 * 调用方负责定位 (x,y) 与垂直居中偏移。</p>
 */
public final class IconBlitHelper {

    private IconBlitHelper() {}

    /**
     * 在 (x,y) 绘制图标，目标尺寸 size×size（像素），自动从 sheet 切对应格。
     * 调用方负责计算垂直居中偏移（如 {@code y + (rowH - size) / 2}）。
     */
    public static void blit(GuiGraphics g, AttributeIconRegistry.Icon icon, int x, int y, int size) {
        g.blit(icon.texture(), x, y, size, size,
               icon.u(), icon.v(), icon.regionW(), icon.regionH(), icon.sheetW(), icon.sheetH());
    }
}
