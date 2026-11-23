package net.minecraft.client.yiz.anim.editor;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.yiz.anim.AnimStorage;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.List;

/**
 * 动画管理：重命名、删除（进回收站）、从回收站恢复（恢复时手动命名）。
 *
 * <p>回收站最多 10 个文件；恢复时旧名字可能已被占用，所以必须由玩家输入新名字。</p>
 */
public class AnimManagerScreen extends Screen {

    private static final int PANEL_W = 560;
    private static final int PANEL_H = 320;
    private static final int ROW_H = 22;

    private int px;
    private int py;
    private EditBox nameBox;
    private String action = "none"; // none / rename / recover
    private String targetName = "";
    private Path targetTrash;

    public AnimManagerScreen() {
        super(Component.literal("动画管理"));
    }

    @Override
    protected void init() {
        this.px = (this.width - PANEL_W) / 2;
        this.py = (this.height - PANEL_H) / 2;

        List<String> names = AnimStorage.animationFiles();
        for (int i = 0; i < Math.min(names.size(), 9); i++) {
            String name = names.get(i);
            int rowY = this.py + 30 + i * ROW_H;
            this.addRenderableWidget(Button.builder(Component.literal("改名"), b -> {
                this.action = "rename";
                this.targetName = name;
                this.nameBox.setValue(name);
                this.nameBox.setFocused(true);
            }).bounds(this.px + 190, rowY, 36, 18).build());
            this.addRenderableWidget(Button.builder(Component.literal("删除"), b -> {
                AnimStorage.moveToTrash(name);
                this.rebuildWidgets();
            }).bounds(this.px + 230, rowY, 36, 18).build());
        }

        List<Path> trash = AnimStorage.trashFiles();
        for (int i = 0; i < Math.min(trash.size(), 9); i++) {
            Path file = trash.get(i);
            int rowY = this.py + 30 + i * ROW_H;
            this.addRenderableWidget(Button.builder(Component.literal("恢复"), b -> {
                this.action = "recover";
                this.targetTrash = file;
                this.nameBox.setValue(trashName(file) + "_restored");
                this.nameBox.setFocused(true);
            }).bounds(this.px + 470, rowY, 36, 18).build());
        }

        this.nameBox = new EditBox(this.font, this.px + 8, this.py + PANEL_H - 26, 220, 18,
                Component.literal("新名称"));
        this.nameBox.setMaxLength(40);
        this.nameBox.setValue("");
        this.addRenderableWidget(this.nameBox);

        this.addRenderableWidget(Button.builder(Component.literal("确定"), b -> this.apply())
                .bounds(this.px + 234, this.py + PANEL_H - 26, 50, 18).build());
        this.addRenderableWidget(Button.builder(Component.literal("取消"), b -> {
            this.action = "none";
            this.targetName = "";
            this.targetTrash = null;
        }).bounds(this.px + 288, this.py + PANEL_H - 26, 50, 18).build());
        this.addRenderableWidget(Button.builder(Component.literal("返回编辑器"), b -> this.onClose())
                .bounds(this.px + 342, this.py + PANEL_H - 26, 80, 18).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);
        graphics.fill(this.px, this.py, this.px + PANEL_W, this.py + PANEL_H, 0xF0101010);

        graphics.drawString(this.font, "动画列表（改名 / 删除到回收站）", this.px + 8, this.py + 14, 0xFFFFDD88, false);
        graphics.drawString(this.font, "回收站（最多10个，恢复需命名）", this.px + 290, this.py + 14, 0xFF88DDFF, false);

        List<String> names = AnimStorage.animationFiles();
        for (int i = 0; i < Math.min(names.size(), 9); i++) {
            graphics.drawString(this.font, names.get(i), this.px + 8, this.py + 35 + i * ROW_H, 0xFFFFFFFF, false);
        }
        List<Path> trash = AnimStorage.trashFiles();
        for (int i = 0; i < Math.min(trash.size(), 9); i++) {
            graphics.drawString(this.font, trashName(trash.get(i)), this.px + 290, this.py + 35 + i * ROW_H, 0xFFCCCCCC, false);
        }

        String state = switch (this.action) {
            case "rename" -> "重命名: " + this.targetName + " → " + this.nameBox.getValue();
            case "recover" -> "恢复为: " + this.nameBox.getValue();
            default -> "选择操作：点「改名」或「恢复」后输入新名字，再点「确定」";
        };
        graphics.drawString(this.font, state, this.px + 8, this.py + PANEL_H - 44, 0xFFAAAAAA, false);

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean isPauseScreen() {
        return true;
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(new AnimEditorScreen());
    }

    private void apply() {
        String newName = this.nameBox.getValue().trim();
        if (newName.isEmpty()) {
            return;
        }
        switch (this.action) {
            case "rename" -> {
                AnimStorage.rename(this.targetName, newName);
                this.action = "none";
                this.rebuildWidgets();
            }
            case "recover" -> {
                AnimStorage.recover(this.targetTrash, newName);
                this.action = "none";
                this.rebuildWidgets();
            }
            default -> {
            }
        }
    }

    private static String trashName(Path file) {
        String name = file.getFileName().toString();
        if (name.endsWith(".json")) {
            name = name.substring(0, name.length() - 5);
        }
        int underscore = name.lastIndexOf('_');
        return underscore > 0 ? name.substring(0, underscore) : name;
    }
}
