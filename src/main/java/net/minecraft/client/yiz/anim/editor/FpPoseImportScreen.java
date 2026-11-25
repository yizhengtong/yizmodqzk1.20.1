package net.minecraft.client.yiz.anim.editor;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.yiz.anim.AnimClip;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 第一人称姿态导入界面：把 {@code config/yizmodqzk/fp_poses/} 里的 a1/a2/a3/a4 文件
 * 按时间帧导入当前动画的 {@code fp_right_arm}/{@code fp_left_arm}。
 *
 * <p>每个文件读取 Blockbench 的 {@code firstperson_righthand}（左手读 {@code firstperson_lefthand}）
 * display transform；时间单位秒，默认按文件顺序均分到动画时长。</p>
 */
public class FpPoseImportScreen extends Screen {

    private static final int MAX_ROWS = 7;
    private static final int ROW_H = 20;

    private final AnimEditorScreen parent;
    private final AnimClip clip;
    private final List<Path> files = new ArrayList<>();
    private final List<Checkbox> checks = new ArrayList<>();
    private final List<EditBox> times = new ArrayList<>();

    public FpPoseImportScreen(AnimEditorScreen parent, AnimClip clip) {
        super(Component.literal("导入第一人称姿态"));
        this.parent = parent;
        this.clip = clip;
    }

    @Override
    protected void init() {
        this.files.clear();
        this.files.addAll(FpPoseImporter.listFiles());
        this.checks.clear();
        this.times.clear();

        int rows = Math.min(this.files.size(), MAX_ROWS);
        for (int i = 0; i < rows; i++) {
            int y = 56 + i * ROW_H;
            Checkbox check = new Checkbox(24, y, 20, 20, Component.empty(), true);
            this.addRenderableWidget(check);
            this.checks.add(check);

            EditBox time = new EditBox(this.font, this.width - 104, y, 72, 18, Component.literal("时间"));
            time.setValue(String.format("%.2f", this.defaultTime(i, rows)));
            this.addRenderableWidget(time);
            this.times.add(time);
        }

        this.addRenderableWidget(Button.builder(Component.literal("导入"), b -> this.apply())
                .bounds(this.width / 2 - 70, this.height - 32, 64, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("取消"), b -> this.onClose())
                .bounds(this.width / 2 + 6, this.height - 32, 64, 20).build());
    }

    private float defaultTime(int index, int count) {
        if (count <= 1) {
            return 0F;
        }
        return this.clip.duration() * index / (count - 1);
    }

    private void apply() {
        List<FpPoseImporter.Entry> entries = new ArrayList<>();
        int count = this.checks.size();
        for (int i = 0; i < count; i++) {
            float time;
            try {
                time = Float.parseFloat(this.times.get(i).getValue().trim());
            } catch (NumberFormatException ignored) {
                time = this.defaultTime(i, count);
            }
            entries.add(new FpPoseImporter.Entry(this.files.get(i), time, this.checks.get(i).selected()));
        }
        if (entries.stream().noneMatch(FpPoseImporter.Entry::enabled)) {
            this.onClose();
            return;
        }
        FpPoseImporter.importTo(this.clip, entries);
        this.parent.onPosesImported();
        this.onClose();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 12, 0xFFFFFF);
        graphics.drawString(this.font, "目录：" + FpPoseImporter.dir(), 20, 30, 0xFF999999, false);
        graphics.drawString(this.font, "读取 display.firstperson_righthand / lefthand；时间单位秒，留空/无效用默认均分",
                20, 42, 0xFF999999, false);

        int rows = Math.min(this.files.size(), MAX_ROWS);
        for (int i = 0; i < rows; i++) {
            int y = 56 + i * ROW_H;
            String name = FpPoseImporter.displayName(this.files.get(i));
            int maxWidth = this.width - 104 - 52;
            if (this.font.width(name) > maxWidth) {
                name = this.font.plainSubstrByWidth(name, maxWidth - 6) + "...";
            }
            graphics.drawString(this.font, name, 48, y + 6, 0xFFDDDDDD, false);
        }
        if (this.files.isEmpty()) {
            graphics.drawString(this.font, "目录里没有 .json / .bbmodel 文件", 24, 64, 0xFFFF6666, false);
            graphics.drawString(this.font, "把 Blockbench 的 a1/a2/a3/a4 放进上面的目录后重新打开本界面",
                    24, 80, 0xFF999999, false);
        } else if (this.files.size() > MAX_ROWS) {
            graphics.drawString(this.font, "文件较多，只显示前 " + MAX_ROWS + " 个（按文件名排序）",
                    24, 56 + MAX_ROWS * ROW_H + 2, 0xFFFFAA00, false);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(this.parent);
        }
    }
}
