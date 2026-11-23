package net.minecraft.client.yiz.anim.editor;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.yiz.anim.AnimClientState;
import net.minecraft.client.yiz.anim.AnimClip;
import net.minecraft.client.yiz.anim.AnimLibrary;
import net.minecraft.client.yiz.anim.BoneTransform;
import net.minecraft.client.yiz.anim.client.AnimSkeletonCapture;
import net.minecraft.network.chat.Component;
import org.joml.Quaternionf;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * UE Sequencer 风格的动画编辑器（全屏）。
 *
 * <pre>
 * ┌──────────── commandBar：新建/保存/播放/关键帧/模式/关闭 ────────────┐
 * │ Navigator │              Viewport（真实玩家实体+物品）              │ Inspector │
 * │ 动画列表  │                                                         │ 旋转 XYZ  │
 * │ 骨骼树    │                  （左键拖旋转 / 滚轮缩放 / 右键平移）    │ 位移 XYZ  │
 * │           ├─────────────────────────────────────────────────────────┤ 缩放 XYZ  │
 * │           │              Timeline（轨道+关键帧+播放头）              │ 时长/最小 │
 * └───────────┴─────────────────────────────────────────────────────────┴───────────┘
 * </pre>
 *
 * <p>视口直接渲染 {@code mc.player}（皮肤/装备/手持物品全部与游戏一致），动画通过
 * {@code PlayerModelAnimMixin} + {@code ItemInHandRendererAnimMixin} 生效，所以所见即所得。</p>
 */
public class AnimEditorScreen extends Screen {

    private static final List<String> BONES = AnimClip.BONES;

    // 区域
    private int barH = 22;
    private int navW = 140;
    private int inspW = 170;
    private int workspaceX;
    private int workspaceW;
    private int viewportY;
    private int viewportH;
    private int timelineY;
    private int timelineH;

    private AnimClip clip;
    private String bone = "right_arm";
    private float time;
    private boolean playing;
    private int mode = 1; // 0 旋转 1 移动 2 缩放（默认移动，与 Blockbench 一致）

    // 相机
    private float yaw = 0F;
    private float pitch = 0F;
    private float zoom = 62F;
    private float panX;
    private float panY;

    // 拖拽
    private boolean orbiting;
    private boolean panning;
    private boolean draggingPlayhead;
    private double lastMouseX;
    private double lastMouseY;

    private ValueSlider rxSlider;
    private ValueSlider rySlider;
    private ValueSlider rzSlider;
    private ValueSlider txSlider;
    private ValueSlider tySlider;
    private ValueSlider tzSlider;
    private ValueSlider sxSlider;
    private ValueSlider sySlider;
    private ValueSlider szSlider;
    private ValueSlider durationSlider;
    private ValueSlider minSlider;

    // 关键帧右键菜单
    private String contextBone = "head";
    private float contextTime;
    private double menuX = -1;
    private double menuY = -1;

    // gizmo 拖拽
    private int draggingAxis = -1;
    private int draggingSign = 1;
    private double dragStartAngle;
    private float dragStartRotation;

    private static final Logger LOGGER = LoggerFactory.getLogger("YizAnimEditor");

    /** 立方体 12 条棱（顶点下标按 x/y/z 三个 bit 组合）。 */
    private static final int[][] BOX_EDGES = {
            {0, 1}, {2, 3}, {4, 5}, {6, 7},
            {0, 2}, {1, 3}, {4, 6}, {5, 7},
            {0, 4}, {1, 5}, {2, 6}, {3, 7}
    };

    /** 每次打开编辑器只打印一次捕获结果，方便对着 latest.log 核对贴合。 */
    private boolean loggedCapture;

    public AnimEditorScreen() {
        super(Component.literal("Yiz 动画编辑器"));
        this.clip = firstOrNew();
    }

    private static AnimClip firstOrNew() {
        List<String> names = AnimLibrary.names();
        if (!names.isEmpty()) {
            AnimClip clip = AnimLibrary.get(names.get(0));
            if (clip != null) {
                return clip.copy();
            }
        }
        return new AnimClip("new_1", 0.5F, 0.15F, false);
    }

    @Override
    protected void init() {
        this.workspaceX = this.navW;
        this.workspaceW = Math.max(200, this.width - this.navW - this.inspW);
        int bodyH = Math.max(120, this.height - this.barH);
        this.viewportY = this.barH;
        this.viewportH = Math.max(100, (int) (bodyH * 0.58F));
        this.timelineY = this.viewportY + this.viewportH + 4;
        this.timelineH = Math.max(60, this.height - this.timelineY);

        int bx = 4;
        int by = 2;
        this.addRenderableWidget(Button.builder(Component.literal("新建"), b -> this.newClip())
                .bounds(bx, by, 40, 18).build());
        this.addRenderableWidget(Button.builder(Component.literal("保存"), b -> this.saveClip())
                .bounds(bx + 44, by, 40, 18).build());
        this.addRenderableWidget(Button.builder(Component.literal(this.playing ? "暂停" : "播放"), b -> {
            this.playing = !this.playing;
            b.setMessage(Component.literal(this.playing ? "暂停" : "播放"));
        }).bounds(bx + 88, by, 40, 18).build());
        this.addRenderableWidget(Button.builder(Component.literal("关键帧+"), b -> this.addKey())
                .bounds(bx + 132, by, 54, 18).build());
        this.addRenderableWidget(Button.builder(Component.literal("关键帧-"), b -> this.removeKey())
                .bounds(bx + 190, by, 54, 18).build());
        this.addRenderableWidget(Button.builder(Component.literal("关闭"), b -> this.onClose())
                .bounds(this.width - 46, by, 42, 18).build());

        // 视口顶部中央：移动 / 缩放 / 旋转（Blockbench 风格）
        int modeWidth = 3 * 44 + 8;
        int modeX = this.workspaceX + (this.workspaceW - modeWidth) / 2;
        int modeY = this.viewportY + 4;
        this.addRenderableWidget(Button.builder(Component.literal("移动"), b -> this.mode = 1)
                .bounds(modeX, modeY, 40, 18).build());
        this.addRenderableWidget(Button.builder(Component.literal("缩放"), b -> this.mode = 2)
                .bounds(modeX + 44, modeY, 40, 18).build());
        this.addRenderableWidget(Button.builder(Component.literal("旋转"), b -> this.mode = 0)
                .bounds(modeX + 88, modeY, 40, 18).build());

        // 导航器：动画切换 + 管理 + 骨骼
        this.addRenderableWidget(Button.builder(Component.literal("▶ " + this.clip.name()), b -> this.nextClip())
                .bounds(4, this.barH + 4, this.navW - 52, 18).build());
        this.addRenderableWidget(Button.builder(Component.literal("管理"), b ->
                        Minecraft.getInstance().setScreen(new AnimManagerScreen()))
                .bounds(this.navW - 44, this.barH + 4, 40, 18).build());
        for (int i = 0; i < BONES.size(); i++) {
            String name = BONES.get(i);
            this.addRenderableWidget(Button.builder(Component.literal((name.equals(this.bone) ? "● " : "") + labelOf(name)), b -> {
                this.bone = name;
                this.rebuildWidgets();
            }).bounds(4, this.barH + 26 + i * 20, this.navW - 8, 18).build());
        }

        // 检查器：旋转/位移/缩放 + 时长
        int ix = this.width - this.inspW + 4;
        int iy = this.barH + 4;
        this.rxSlider = slider(ix, iy, "X 旋转", -180, 180, 0);
        this.rySlider = slider(ix, iy + 16, "Y 旋转", -180, 180, 0);
        this.rzSlider = slider(ix, iy + 32, "Z 旋转", -180, 180, 0);
        this.txSlider = slider(ix, iy + 54, "X 位移", -1, 1, 0);
        this.tySlider = slider(ix, iy + 70, "Y 位移", -1, 1, 0);
        this.tzSlider = slider(ix, iy + 86, "Z 位移", -1, 1, 0);
        this.sxSlider = slider(ix, iy + 108, "X 缩放", 0.2F, 3F, 1);
        this.sySlider = slider(ix, iy + 124, "Y 缩放", 0.2F, 3F, 1);
        this.szSlider = slider(ix, iy + 140, "Z 缩放", 0.2F, 3F, 1);
        this.durationSlider = this.slider(ix, iy + 168, "时长", 0.1F, 2F, 0.5F, v -> {
            this.clip.setDuration(v);
            this.updatePreview();
        });
        this.minSlider = this.slider(ix, iy + 184, "最小时长", 0.05F, 1F, 0.15F, v -> {
            this.clip.setMinDuration(v);
            this.updatePreview();
        });

        this.syncFromClip();
        this.updatePreview();
    }

    private ValueSlider slider(int x, int y, String label, float min, float max, float value) {
        return this.slider(x, y, label, min, max, value, v -> this.writeKey());
    }

    private ValueSlider slider(int x, int y, String label, float min, float max, float value,
                               java.util.function.Consumer<Float> onChange) {
        return this.addRenderableWidget(new ValueSlider(x, y, this.inspW - 8, 14, label, min, max, value, onChange));
    }

    @Override
    public void tick() {
        if (this.playing) {
            this.time += 1F / 20F;
            if (this.time > this.clip.duration()) {
                this.time = 0F;
            }
            this.syncFromClip();
            this.updatePreview();
        }
    }

    // ───────────────────────── 渲染 ─────────────────────────

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);
        int bh = this.barH;
        graphics.fill(0, 0, this.width, bh, 0xFF202020);
        graphics.fill(0, bh, this.navW, this.height, 0xFF141414);
        graphics.fill(this.width - this.inspW, bh, this.width, this.height, 0xFF141414);
        graphics.fill(this.workspaceX, this.viewportY, this.workspaceX + this.workspaceW, this.timelineY, 0xFF1B1B1B);
        graphics.fill(this.workspaceX, this.timelineY, this.workspaceX + this.workspaceW, this.height, 0xFF101010);

        this.renderViewport(graphics);
        this.renderTimeline(graphics);

        String modeName = switch (this.mode) {
            case 1 -> "移动";
            case 2 -> "缩放";
            default -> "旋转";
        };
        graphics.drawString(this.font,
                "Yiz Anim | " + this.clip.name() + " | 时间 " + String.format("%.2f", this.time)
                        + "s | 骨骼 " + labelOf(this.bone) + " | 模式 " + modeName
                        + " | 关键帧 " + this.clip.keyframes(this.bone).size(),
                4, this.height - 10, 0xFFAAAAAA, false);

        super.render(graphics, mouseX, mouseY, partialTick);
        this.renderGizmo(graphics);
        this.renderContextMenu(graphics);
    }

    private void renderViewport(GuiGraphics graphics) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        int cx = this.workspaceX + this.workspaceW / 2;
        int cy = this.viewportY + (int) (this.viewportH * 0.72F);
        PoseStack pose = graphics.pose();
        // 渲染前把玩家朝向全部归零：否则 yHeadRot 跟随鼠标，视口里的头会歪
        float savedYRot = mc.player.getYRot();
        float savedYRotO = mc.player.yRotO;
        float savedYHeadRot = mc.player.yHeadRot;
        float savedYHeadRotO = mc.player.yHeadRotO;
        float savedYBodyRot = mc.player.yBodyRot;
        float savedYBodyRotO = mc.player.yBodyRotO;
        mc.player.setYRot(0F);
        mc.player.yRotO = 0F;
        mc.player.yHeadRot = 0F;
        mc.player.yHeadRotO = 0F;
        mc.player.yBodyRot = 0F;
        mc.player.yBodyRotO = 0F;
        try {
            pose.pushPose();
            pose.translate(cx + this.panX, cy + this.panY, 50.0D);
            pose.scale(this.zoom, this.zoom, -this.zoom);
            pose.mulPose(this.cameraRotation());
            Lighting.setupForEntityInInventory();
            EntityRenderDispatcher dispatcher = mc.getEntityRenderDispatcher();
            dispatcher.setRenderShadow(false);
            // 捕获窗口：只在这段渲染里记录 ModelPart 的最终矩阵与真实 cube 几何
            AnimSkeletonCapture.begin();
            try {
                RenderSystem.runAsFancy(() -> dispatcher.render(mc.player, 0.0D, 0.0D, 0.0D, 0.0F, 1.0F,
                        pose, graphics.bufferSource(), LightTexture.FULL_BRIGHT));
            } finally {
                AnimSkeletonCapture.end();
            }
            graphics.flush();
            dispatcher.setRenderShadow(true);
            pose.popPose();
            Lighting.setupFor3DItems();
            this.logCaptureOnce();
        } finally {
            mc.player.setYRot(savedYRot);
            mc.player.yRotO = savedYRotO;
            mc.player.yHeadRot = savedYHeadRot;
            mc.player.yHeadRotO = savedYHeadRotO;
            mc.player.yBodyRot = savedYBodyRot;
            mc.player.yBodyRotO = savedYBodyRotO;
        }
    }

    private void renderTimeline(GuiGraphics graphics) {
        int left = this.workspaceX + 64;
        int right = this.workspaceX + this.workspaceW - 8;
        int top = this.timelineY + 6;
        int rowH = Math.max(12, Math.min(18, (this.timelineH - 24) / BONES.size()));

        // 时间刻度
        graphics.drawString(this.font, "0.0", left, top - 4, 0xFF777777, false);
        graphics.drawString(this.font, String.format("%.2f", this.clip.duration()), right - 22, top - 4, 0xFF777777, false);

        for (int i = 0; i < BONES.size(); i++) {
            String name = BONES.get(i);
            int rowY = top + 10 + i * rowH;
            graphics.drawString(this.font, labelOf(name), this.workspaceX + 4, rowY + 2,
                    name.equals(this.bone) ? 0xFFFFFF55 : 0xFF888888, false);
            graphics.fill(left, rowY + 6, right, rowY + 7, 0xFF333333);
            for (AnimClip.Keyframe frame : this.clip.keyframes(name)) {
                int x = this.timeToX(frame.time());
                // 更细的关键帧点：1px 宽，选中骨骼用亮黄
                graphics.fill(x, rowY + 4, x + 1, rowY + 9, name.equals(this.bone) ? 0xFFFFCC33 : 0xFF66AAFF);
            }
        }
        int playheadX = this.timeToX(this.time);
        graphics.fill(playheadX - 1, top + 6, playheadX + 1, this.height - 6, 0xFFFF5555);
    }

    private Quaternionf cameraRotation() {
        Quaternionf rotation = new Quaternionf().rotateZ((float) Math.PI);
        rotation.mul(new Quaternionf().rotateX((float) Math.toRadians(this.pitch)));
        rotation.mul(new Quaternionf().rotateY((float) Math.toRadians(this.yaw)));
        return rotation;
    }

    // ───────────────────────── 几何 ─────────────────────────

    private int timeToX(float t) {
        int left = this.workspaceX + 64;
        int right = this.workspaceX + this.workspaceW - 8;
        float p = this.clip.duration() <= 0F ? 0F : Math.max(0F, Math.min(1F, t / this.clip.duration()));
        return left + (int) (p * (right - left));
    }

    private float xToTime(double x) {
        int left = this.workspaceX + 64;
        int right = this.workspaceX + this.workspaceW - 8;
        float p = (float) ((x - left) / Math.max(1.0, right - left));
        return Math.max(0F, Math.min(1F, p)) * this.clip.duration();
    }

    private int rowAt(double mouseY) {
        int top = this.timelineY + 6;
        int rowH = Math.max(12, Math.min(18, (this.timelineH - 24) / BONES.size()));
        int index = (int) ((mouseY - (top + 10)) / rowH);
        return index < 0 || index >= BONES.size() ? -1 : index;
    }

    private boolean inViewport(double mx, double my) {
        return mx >= this.workspaceX && mx <= this.workspaceX + this.workspaceW
                && my >= this.viewportY && my <= this.timelineY;
    }

    private boolean inTimeline(double mx, double my) {
        return mx >= this.workspaceX && mx <= this.workspaceX + this.workspaceW
                && my >= this.timelineY && my <= this.height;
    }

    // ───────────────────────── 鼠标 ─────────────────────────

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // 右键上下文菜单优先
        if (this.menuX >= 0) {
            if (this.handleMenuClick(mouseX, mouseY)) {
                return true;
            }
            this.closeMenu();
        }
        // 先让按钮/滑条处理（视口顶部的模式按钮落在视口区域内）
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (this.inViewport(mouseX, mouseY)) {
            if (button == 0) {
                int axis = this.hitGizmoAxis(mouseX, mouseY);
                if (axis >= 0) {
                    this.draggingAxis = axis;
                    if (this.mode == 0) {
                        int[] origin = this.boneScreenPos(this.bone);
                        if (origin == null) {
                            return true;
                        }
                        this.dragStartAngle = Math.atan2(mouseY - origin[1], mouseX - origin[0]);
                        BoneTransform current = this.clip.sample(this.bone, this.time);
                        this.dragStartRotation = axis == 0 ? current.rx() : axis == 1 ? current.ry() : current.rz();
                    }
                    this.lastMouseX = mouseX;
                    this.lastMouseY = mouseY;
                    return true;
                }
                // 点模型不选骨骼（统一用左侧按钮选择），点空白旋转视角
                this.orbiting = true;
            } else if (button == 1) {
                this.panning = true;
            }
            this.lastMouseX = mouseX;
            this.lastMouseY = mouseY;
            return true;
        }
        if (this.inTimeline(mouseX, mouseY) && button == 0) {
            // bug 修复：拖动时间轴只改时间，不改变当前选中的骨骼
            this.time = this.xToTime(mouseX);
            this.draggingPlayhead = true;
            this.syncFromClip();
            this.updatePreview();
            return true;
        }
        if (this.inTimeline(mouseX, mouseY) && button == 1) {
            int row = this.rowAt(mouseY);
            if (row >= 0) {
                String rowBone = BONES.get(row);
                AnimClip.Keyframe hit = this.nearestKeyframe(rowBone, mouseX);
                if (hit != null) {
                    this.contextBone = rowBone;
                    this.contextTime = hit.time();
                    this.menuX = mouseX;
                    this.menuY = mouseY;
                    return true;
                }
            }
            this.closeMenu();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private AnimClip.Keyframe nearestKeyframe(String bone, double mouseX) {
        AnimClip.Keyframe best = null;
        double bestDistance = 5.0D;
        for (AnimClip.Keyframe frame : this.clip.keyframes(bone)) {
            double distance = Math.abs(this.timeToX(frame.time()) - mouseX);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = frame;
            }
        }
        return best;
    }

    private boolean handleMenuClick(double mouseX, double mouseY) {
        int mx = (int) this.menuX;
        int my = (int) this.menuY;
        if (mouseX < mx || mouseX > mx + 116 || mouseY < my || mouseY > my + 34) {
            return false;
        }
        if (mouseY < my + 17) {
            this.clip.removeKeyframe(this.contextBone, this.contextTime);
            this.notify("已删除关键帧");
        } else {
            BoneTransform value = this.clip.sample(this.contextBone, this.contextTime);
            this.clip.setKeyframe(this.contextBone, this.time, value);
            this.notify("已复制到当前时间 " + String.format("%.2f", this.time) + "s");
        }
        this.closeMenu();
        this.updatePreview();
        return true;
    }

    private void closeMenu() {
        this.menuX = -1;
        this.menuY = -1;
    }

    private void renderContextMenu(GuiGraphics graphics) {
        if (this.menuX < 0) {
            return;
        }
        int mx = (int) this.menuX;
        int my = (int) this.menuY;
        graphics.fill(mx, my, mx + 116, my + 34, 0xF0222222);
        graphics.fill(mx, my, mx + 116, my + 1, 0xFF666666);
        graphics.drawString(this.font, "删除关键帧", mx + 5, my + 4, 0xFFFFDDDD, false);
        graphics.drawString(this.font, "复制到当前时间", mx + 5, my + 18, 0xFFDDFFDD, false);
    }

    // ───────────────────────── gizmo / 点击拾取 ─────────────────────────

    private PlayerModel<?> viewModel() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return null;
        }
        EntityRenderer<?> renderer = mc.getEntityRenderDispatcher().getRenderer(mc.player);
        return renderer instanceof PlayerRenderer playerRenderer ? playerRenderer.getModel() : null;
    }

    /**
     * 取当前选中骨骼的真实几何（上一帧视口渲染时由 {@code ModelPart#render} 捕获）。
     * 矩阵已经是"骨骼局部坐标（格）→ GUI 像素"，所以这里不再做任何 rotateY/scale/translate 推导。
     */
    private AnimSkeletonCapture.BoneFrame frame(String bone) {
        PlayerModel<?> model = this.viewModel();
        if (model == null) {
            return null;
        }
        return switch (bone) {
            case "head" -> AnimSkeletonCapture.get(model.head);
            case "body" -> AnimSkeletonCapture.get(model.body);
            case "right_arm" -> AnimSkeletonCapture.get(model.rightArm);
            case "left_arm" -> AnimSkeletonCapture.get(model.leftArm);
            case "right_leg" -> AnimSkeletonCapture.get(model.rightLeg);
            case "left_leg" -> AnimSkeletonCapture.get(model.leftLeg);
            // 物品点没有自己的 cube：挂在手末端（手臂局部 +y 指向手，cube 到 10/16 格）
            case "right_item" -> {
                AnimSkeletonCapture.BoneFrame arm = AnimSkeletonCapture.get(model.rightArm);
                yield arm == null ? null : arm.offset(0F, 10F / 16F, 0F).box(0.12F);
            }
            case "left_item" -> {
                AnimSkeletonCapture.BoneFrame arm = AnimSkeletonCapture.get(model.leftArm);
                yield arm == null ? null : arm.offset(0F, 10F / 16F, 0F).box(0.12F);
            }
            default -> null;
        };
    }

    private int[] boneScreenPos(String bone) {
        AnimSkeletonCapture.BoneFrame frame = this.frame(bone);
        return frame == null ? null : toScreen(frame.pivot());
    }

    private static int[] toScreen(Vector3f point) {
        return new int[]{Math.round(point.x), Math.round(point.y)};
    }

    private int[] project(AnimSkeletonCapture.BoneFrame frame, float x, float y, float z) {
        return toScreen(frame.project(x, y, z));
    }

    /** 三根骨骼局部轴在屏幕上的单位方向（含父链/原版姿态/动画）。 */
    private Vector2f[] axisDirections(String bone) {
        AnimSkeletonCapture.BoneFrame frame = this.frame(bone);
        if (frame == null) {
            return null;
        }
        Vector2f[] result = new Vector2f[3];
        for (int i = 0; i < 3; i++) {
            result[i] = frame.screenAxis(i);
        }
        return result;
    }

    private int hitGizmoAxis(double mouseX, double mouseY) {
        if (!this.inViewport(mouseX, mouseY)) {
            return -1;
        }
        AnimSkeletonCapture.BoneFrame frame = this.frame(this.bone);
        int[] origin = this.boneScreenPos(this.bone);
        Vector2f[] dirs = this.axisDirections(this.bone);
        if (frame == null || origin == null || dirs == null) {
            return -1;
        }
        int len = 40;
        this.draggingSign = 1;
        if (this.mode == 2) {
            // 缩放：6 个面把手（±X/±Y/±Z）
            int best = -1;
            double bestDistance = 8.0D;
            for (int i = 0; i < 3; i++) {
                for (int sign = -1; sign <= 1; sign += 2) {
                    double ex = origin[0] + dirs[i].x * len * sign;
                    double ey = origin[1] + dirs[i].y * len * sign;
                    double d = Math.hypot(mouseX - ex, mouseY - ey);
                    if (d < bestDistance) {
                        bestDistance = d;
                        best = i;
                        this.draggingSign = sign;
                    }
                }
            }
            return best;
        }
        if (this.mode == 0) {
            // 旋转：三条圆环
            int best = -1;
            double bestDistance = 7.0D;
            for (int i = 0; i < 3; i++) {
                double d = this.distanceToRing(mouseX, mouseY, frame, i, 36);
                if (d < bestDistance) {
                    bestDistance = d;
                    best = i;
                }
            }
            return best;
        }
        // 移动：三条箭头轴
        int best = -1;
        double bestDistance = 8.0D;
        for (int i = 0; i < 3; i++) {
            double ex = origin[0] + dirs[i].x * len;
            double ey = origin[1] + dirs[i].y * len;
            double d = distanceToSegment(mouseX, mouseY, origin[0], origin[1], ex, ey);
            if (d < bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        return best;
    }

    private int[] ringPoint(AnimSkeletonCapture.BoneFrame frame, int axis, double angle, int radius) {
        float r = radius / Math.max(1F, this.zoom);
        double c = Math.cos(angle) * r;
        double s = Math.sin(angle) * r;
        // 圆环平面：绕 X 旋转 → YZ 平面；绕 Y → XZ；绕 Z → XY
        float x = axis == 0 ? 0F : (float) c;
        float y = axis == 0 ? (float) c : axis == 1 ? 0F : (float) s;
        float z = axis == 2 ? 0F : (float) s;
        return this.project(frame, x, y, z);
    }

    private double distanceToRing(double mouseX, double mouseY, AnimSkeletonCapture.BoneFrame frame, int axis, int radius) {
        double best = Double.MAX_VALUE;
        for (int i = 0; i < 48; i++) {
            int[] point = this.ringPoint(frame, axis, i * Math.PI * 2.0 / 48.0, radius);
            best = Math.min(best, Math.hypot(mouseX - point[0], mouseY - point[1]));
        }
        return best;
    }

    private void applyAxisDrag(int axis, float pixelDelta) {
        BoneTransform t = this.clip.sample(this.bone, this.time);
        BoneTransform updated;
        if (this.mode == 1) {
            float v = clamp(axisValue(t, 1, axis) + pixelDelta * 0.008F, -1F, 1F);
            updated = axis == 0 ? t.withPosition(v, t.ty(), t.tz())
                    : axis == 1 ? t.withPosition(t.tx(), v, t.tz())
                    : t.withPosition(t.tx(), t.ty(), v);
        } else if (this.mode == 2) {
            float v = clamp(axisValue(t, 2, axis) + pixelDelta * 0.01F, 0.5F, 2F);
            updated = axis == 0 ? t.withScale(v, t.sy(), t.sz())
                    : axis == 1 ? t.withScale(t.sx(), v, t.sz())
                    : t.withScale(t.sx(), t.sy(), v);
        } else {
            // 世界方向轴 → 模型空间轴：x/y 镜像
            float sign = axis == 2 ? 1F : -1F;
            float v = clamp(axisValue(t, 0, axis) + pixelDelta * 0.9F * sign, -180F, 180F);
            updated = axis == 0 ? t.withRotation(v, t.ry(), t.rz())
                    : axis == 1 ? t.withRotation(t.rx(), v, t.rz())
                    : t.withRotation(t.rx(), t.ry(), v);
        }
        this.clip.setKeyframe(this.bone, this.time, updated);
        this.syncFromClip();
        this.updatePreview();
    }

    private static float axisValue(BoneTransform t, int mode, int axis) {
        if (mode == 1) {
            return axis == 0 ? t.tx() : axis == 1 ? t.ty() : t.tz();
        }
        if (mode == 2) {
            return axis == 0 ? t.sx() : axis == 1 ? t.sy() : t.sz();
        }
        return axis == 0 ? t.rx() : axis == 1 ? t.ry() : t.rz();
    }



    /** 选中骨骼：黄色线框（模型自己的 cube 真实几何）+ Blockbench 风格 gizmo。 */
    private void renderGizmo(GuiGraphics graphics) {
        AnimSkeletonCapture.BoneFrame frame = this.frame(this.bone);
        if (frame == null) {
            return;
        }
        this.renderBoneHighlight(graphics, frame);
        Vector2f[] dirs = this.axisDirections(this.bone);
        if (dirs == null) {
            return;
        }
        int[] origin = toScreen(frame.pivot());
        int[] colors = {0xFFFF5555, 0xFF55FF55, 0xFF5599FF};
        int len = 40;
        if (this.mode == 2) {
            // 缩放：6 个面把手
            for (int i = 0; i < 3; i++) {
                for (int sign = -1; sign <= 1; sign += 2) {
                    int ex = Math.round(origin[0] + dirs[i].x * len * sign);
                    int ey = Math.round(origin[1] + dirs[i].y * len * sign);
                    graphics.fill(ex - 3, ey - 3, ex + 4, ey + 4, colors[i]);
                }
            }
        } else if (this.mode == 0) {
            // 旋转：三条圆环（在骨骼局部坐标系里，随骨骼动画一起转）
            for (int i = 0; i < 3; i++) {
                int[] prev = null;
                for (int s = 0; s <= 48; s++) {
                    int[] point = this.ringPoint(frame, i, s * Math.PI * 2.0 / 48.0, 36);
                    if (prev != null) {
                        drawLine(graphics, prev[0], prev[1], point[0], point[1], colors[i]);
                    }
                    prev = point;
                }
            }
        } else {
            // 移动：三条箭头轴
            for (int i = 0; i < 3; i++) {
                int ex = Math.round(origin[0] + dirs[i].x * len);
                int ey = Math.round(origin[1] + dirs[i].y * len);
                drawLine(graphics, origin[0], origin[1], ex, ey, colors[i]);
                graphics.fill(ex - 2, ey - 2, ex + 3, ey + 3, colors[i]);
                drawLine(graphics, ex, ey,
                        ex - (int) (dirs[i].x * 9 - dirs[i].y * 5) - 1,
                        ey - (int) (dirs[i].y * 9 + dirs[i].x * 5) - 1, colors[i]);
                drawLine(graphics, ex, ey,
                        ex - (int) (dirs[i].x * 9 + dirs[i].y * 5) - 1,
                        ey - (int) (dirs[i].y * 9 - dirs[i].x * 5) - 1, colors[i]);
            }
        }
        // 枢轴点
        graphics.fill(origin[0] - 2, origin[1] - 2, origin[0] + 3, origin[1] + 3, 0xFFFFFFFF);
    }

    /** 选中骨骼的黄色包围盒：直接投影捕获到的 cube 的 8 个角点，画 12 条棱。 */
    private void renderBoneHighlight(GuiGraphics graphics, AnimSkeletonCapture.BoneFrame frame) {
        int color = 0xFFFFD700;
        for (AnimSkeletonCapture.Box box : frame.boxes()) {
            int[][] corners = new int[8][];
            for (int i = 0; i < 8; i++) {
                float x = (i & 1) == 0 ? box.minX : box.maxX;
                float y = (i & 2) == 0 ? box.minY : box.maxY;
                float z = (i & 4) == 0 ? box.minZ : box.maxZ;
                corners[i] = this.project(frame, x, y, z);
            }
            for (int[] edge : BOX_EDGES) {
                drawLine(graphics, corners[edge[0]][0], corners[edge[0]][1],
                        corners[edge[1]][0], corners[edge[1]][1], color);
            }
        }
    }

    /** 打开编辑器后第一次成功捕获时打一行日志，便于对着 latest.log 确认 gizmo 与模型在同一位置。 */
    private void logCaptureOnce() {
        if (this.loggedCapture) {
            return;
        }
        PlayerModel<?> model = this.viewModel();
        if (model == null || AnimSkeletonCapture.get(model.head) == null) {
            return;
        }
        this.loggedCapture = true;
        StringBuilder pivots = new StringBuilder();
        for (String name : BONES) {
            AnimSkeletonCapture.BoneFrame frame = this.frame(name);
            if (frame == null) {
                pivots.append(name).append("=null ");
                continue;
            }
            Vector3f point = frame.pivot();
            pivots.append(name).append("=(").append(Math.round(point.x)).append(',')
                    .append(Math.round(point.y)).append(") ");
        }
        StringBuilder headBox = new StringBuilder();
        AnimSkeletonCapture.BoneFrame head = AnimSkeletonCapture.get(model.head);
        for (AnimSkeletonCapture.Box box : head.boxes()) {
            headBox.append('[');
            for (int i = 0; i < 8; i++) {
                float x = (i & 1) == 0 ? box.minX : box.maxX;
                float y = (i & 2) == 0 ? box.minY : box.maxY;
                float z = (i & 4) == 0 ? box.minZ : box.maxZ;
                Vector3f point = head.project(x, y, z);
                headBox.append('(').append(Math.round(point.x)).append(',')
                        .append(Math.round(point.y)).append(')');
            }
            headBox.append("] ");
        }
        LOGGER.info("[AnimEditor] 骨骼捕获成功（矩阵来自 ModelPart#render）: 视口中心=({},{}) zoom={} | 枢轴屏幕坐标: {}| 头部 cube 8 角: {}",
                this.workspaceX + this.workspaceW / 2,
                this.viewportY + (int) (this.viewportH * 0.72F),
                this.zoom, pivots, headBox.toString().trim());
    }

    private static void drawLine(GuiGraphics graphics, int x0, int y0, int x1, int y1, int color) {
        int dx = x1 - x0;
        int dy = y1 - y0;
        int steps = Math.max(Math.abs(dx), Math.abs(dy));
        if (steps <= 0) {
            return;
        }
        for (int i = 0; i <= steps; i++) {
            int x = x0 + dx * i / steps;
            int y = y0 + dy * i / steps;
            graphics.fill(x, y, x + 2, y + 2, color);
        }
    }

    private static double distanceToSegment(double px, double py, double x0, double y0, double x1, double y1) {
        double dx = x1 - x0;
        double dy = y1 - y0;
        double lenSq = dx * dx + dy * dy;
        double t = lenSq <= 1.0E-6D ? 0D : ((px - x0) * dx + (py - y0) * dy) / lenSq;
        t = Math.max(0D, Math.min(1D, t));
        double cx = x0 + t * dx;
        double cy = y0 + t * dy;
        return Math.hypot(px - cx, py - cy);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        this.orbiting = false;
        this.panning = false;
        this.draggingPlayhead = false;
        this.draggingAxis = -1;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        double dx = mouseX - this.lastMouseX;
        double dy = mouseY - this.lastMouseY;
        this.lastMouseX = mouseX;
        this.lastMouseY = mouseY;
        if (this.orbiting) {
            this.yaw += (float) dx * 0.6F;
            this.pitch = clamp(this.pitch + (float) dy * 0.6F, -89F, 89F);
            return true;
        }
        if (this.panning) {
            this.panX += (float) dx;
            this.panY += (float) dy;
            return true;
        }
        if (this.draggingAxis >= 0) {
            if (this.mode == 0) {
                int[] origin = this.boneScreenPos(this.bone);
                if (origin == null) {
                    return true;
                }
                double angle = Math.atan2(mouseY - origin[1], mouseX - origin[0]);
                float deltaDegrees = (float) Math.toDegrees(angle - this.dragStartAngle);
                BoneTransform t = this.clip.sample(this.bone, this.time);
                float axisSign = this.draggingAxis == 2 ? 1F : -1F;
                float v = clamp(this.dragStartRotation + deltaDegrees * this.draggingSign * axisSign, -180F, 180F);
                BoneTransform updated = this.draggingAxis == 0 ? t.withRotation(v, t.ry(), t.rz())
                        : this.draggingAxis == 1 ? t.withRotation(t.rx(), v, t.rz())
                        : t.withRotation(t.rx(), t.ry(), v);
                this.clip.setKeyframe(this.bone, this.time, updated);
                this.syncFromClip();
                this.updatePreview();
            } else {
                Vector2f[] dragDirs = this.axisDirections(this.bone);
                if (dragDirs == null) {
                    return true;
                }
                Vector2f dir = dragDirs[this.draggingAxis];
                float delta = (float) (dx * dir.x + dy * dir.y) * this.draggingSign;
                this.applyAxisDrag(this.draggingAxis, delta);
            }
            return true;
        }
        if (this.draggingPlayhead) {
            this.time = this.xToTime(mouseX);
            this.syncFromClip();
            this.updatePreview();
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (this.inViewport(mouseX, mouseY)) {
            this.zoom = clamp(this.zoom * (1F + (float) delta * 0.12F), 18F, 160F);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 83 && (modifiers & 2) != 0) { // Ctrl+S 保存（必须放在 S 之前）
            this.saveClip();
            return true;
        }
        if (keyCode == 32) { // Space 播放/暂停
            this.playing = !this.playing;
            return true;
        }
        if (keyCode == 75) { // K 关键帧
            this.addKey();
            return true;
        }
        if (keyCode == 261 || keyCode == 259) { // Delete/Backspace
            this.removeKey();
            return true;
        }
        if (keyCode == 71) { // G 移动
            this.mode = 1;
            return true;
        }
        if (keyCode == 82) { // R 旋转
            this.mode = 0;
            return true;
        }
        if (keyCode == 83) { // S 缩放
            this.mode = 2;
            return true;
        }
        if (keyCode == 263) { // ←
            this.step(-1);
            return true;
        }
        if (keyCode == 262) { // →
            this.step(1);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        AnimClientState.clearPreview();
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return true;
    }

    // ───────────────────────── 编辑逻辑 ─────────────────────────

    private void step(int frames) {
        this.time = clamp(this.time + frames / 20F, 0F, this.clip.duration());
        this.syncFromClip();
        this.updatePreview();
    }

    private void addKey() {
        this.clip.setKeyframe(this.bone, this.time, this.currentTransform());
        this.updatePreview();
    }

    private void removeKey() {
        this.clip.removeKeyframe(this.bone, this.time);
        this.updatePreview();
    }

    private BoneTransform currentTransform() {
        return new BoneTransform(
                this.rxSlider.value(), this.rySlider.value(), this.rzSlider.value(),
                this.txSlider.value(), this.tySlider.value(), this.tzSlider.value(),
                this.sxSlider.value(), this.sySlider.value(), this.szSlider.value());
    }

    private void writeKey() {
        if (this.rxSlider == null) {
            return;
        }
        this.clip.setKeyframe(this.bone, this.time, this.currentTransform());
        this.updatePreview();
    }

    private void syncFromClip() {
        if (this.rxSlider == null) {
            return;
        }
        BoneTransform t = this.clip.sample(this.bone, this.time);
        this.rxSlider.setFloat(t.rx());
        this.rySlider.setFloat(t.ry());
        this.rzSlider.setFloat(t.rz());
        this.txSlider.setFloat(t.tx());
        this.tySlider.setFloat(t.ty());
        this.tzSlider.setFloat(t.tz());
        this.sxSlider.setFloat(t.sx());
        this.sySlider.setFloat(t.sy());
        this.szSlider.setFloat(t.sz());
        this.durationSlider.setFloat(this.clip.duration());
        this.minSlider.setFloat(this.clip.minDuration());
    }

    private void updatePreview() {
        AnimClientState.setPreview(this.clip, this.time);
    }

    private void newClip() {
        String name = "new_" + (AnimLibrary.names().size() + 1);
        this.clip = new AnimClip(name, 0.5F, 0.15F, false);
        this.time = 0F;
        this.rebuildWidgets();
    }

    private void nextClip() {
        List<String> names = new ArrayList<>(AnimLibrary.names());
        if (names.isEmpty()) {
            return;
        }
        int index = names.indexOf(this.clip.name());
        String next = names.get((index + 1) % names.size());
        AnimClip clip = AnimLibrary.get(next);
        if (clip != null) {
            this.clip = clip.copy();
            this.time = 0F;
            this.rebuildWidgets();
        }
    }

    private void saveClip() {
        this.clip.setDuration(this.durationSlider.value());
        this.clip.setMinDuration(this.minSlider.value());
        try {
            AnimLibrary.save(this.clip);
            this.notify("已保存 " + this.clip.name());
        } catch (Throwable t) {
            this.notify("保存失败: " + t.getMessage());
        }
    }

    private void notify(String message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal("[动画] " + message), false);
        }
    }

    private static String labelOf(String bone) {
        return switch (bone) {
            case "head" -> "头";
            case "body" -> "身体";
            case "right_arm" -> "右臂";
            case "left_arm" -> "左臂";
            case "right_leg" -> "右腿";
            case "left_leg" -> "左腿";
            case "right_item" -> "右手物品点";
            case "left_item" -> "左手物品点";
            default -> bone;
        };
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    /** 数值滑条：拖动即写当前时间点的关键帧。 */
    private static final class ValueSlider extends AbstractSliderButton {

        private final String label;
        private final float min;
        private final float max;
        private final java.util.function.Consumer<Float> onChange;

        ValueSlider(int x, int y, int width, int height, String label,
                    float min, float max, float value, java.util.function.Consumer<Float> onChange) {
            super(x, y, width, height, Component.empty(), toNorm(value, min, max));
            this.label = label;
            this.min = min;
            this.max = max;
            this.onChange = onChange;
            this.updateMessage();
        }

        float value() {
            return this.min + (float) Math.max(0D, Math.min(1D, this.value)) * (this.max - this.min);
        }

        void setFloat(float value) {
            this.value = toNorm(value, this.min, this.max);
            this.updateMessage();
        }

        private static double toNorm(float value, float min, float max) {
            return max <= min ? 0D : (Math.max(min, Math.min(max, value)) - min) / (double) (max - min);
        }

        @Override
        protected void updateMessage() {
            this.setMessage(Component.literal((this.label == null ? "" : this.label)
                    + ": " + String.format("%.1f", value())));
        }

        @Override
        protected void applyValue() {
            if (this.onChange != null) {
                this.onChange.accept(value());
            }
        }
    }
}
