package net.minecraft.client.yiz.anim.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.yiz.anim.AnimClientState;
import net.minecraft.client.yiz.anim.AnimConfig;
import net.minecraft.client.yiz.anim.AnimLibrary;
import net.minecraft.client.yiz.anim.BoneTransform;
import net.minecraft.client.yiz.anim.network.C2SAnimAttackPayload;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** 动画系统客户端入口：攻击输入、状态清理、第一人称手臂、K 面板。 */
@Mod.EventBusSubscriber(modid = net.minecraft.client.yiz.tizMod.MODID,
        bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class AnimClientEvents {

    /** 第一人称手臂旋转衰减（模型骨骼角直接搬到相机空间会过猛）。 */
    private static final float FIRST_PERSON_SCALE = 0.65F;

    private AnimClientEvents() {}

    @SubscribeEvent
    public static void onInteraction(InputEvent.InteractionKeyMappingTriggered event) {
        boolean wantAttack = AnimConfig.triggersOnAttack() && event.isAttack();
        boolean wantUse = AnimConfig.triggersOnUse() && event.isUseItem();
        if (!wantAttack && !wantUse) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            return;
        }
        if (mc.hitResult != null && mc.hitResult.getType() == HitResult.Type.BLOCK) {
            return;
        }
        if (!AnimLibrary.isAnimWeapon(player.getMainHandItem())) {
            return;
        }
        C2SAnimAttackPayload.send();
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        AnimClientState.tick();
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen == null && mc.player != null
                && net.minecraft.client.yiz.anim.editor.AnimEditorKey.OPEN.consumeClick()) {
            mc.setScreen(new net.minecraft.client.yiz.anim.editor.AnimEditorScreen());
        }
    }

    @SubscribeEvent
    public static void onRenderHand(RenderHandEvent event) {
        if (event.getHand() != InteractionHand.MAIN_HAND) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) {
            return;
        }
        BoneTransform arm = AnimClientState.sample(player.getId(), "right_arm");
        if (arm.isZero()) {
            return;
        }
        PoseStack pose = event.getPoseStack();
        pose.mulPose(Axis.ZP.rotationDegrees(arm.rz() * FIRST_PERSON_SCALE));
        pose.mulPose(Axis.YP.rotationDegrees(arm.ry() * FIRST_PERSON_SCALE));
        pose.mulPose(Axis.XP.rotationDegrees(arm.rx() * FIRST_PERSON_SCALE));
    }
}
