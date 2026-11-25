package net.minecraft.client.yiz.anim.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.yiz.anim.AnimClientState;
import net.minecraft.client.yiz.anim.AnimClip;
import net.minecraft.client.yiz.anim.AnimConfig;
import net.minecraft.client.yiz.anim.AnimLibrary;
import net.minecraft.client.yiz.anim.network.C2SAnimAttackPayload;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 动画系统客户端入口：攻击输入、状态清理、K 面板。
 *
 * <p>第一/第三人称在这里就分开了：</p>
 * <ul>
 *   <li><b>第三人称</b>：发 C2S 包，由服务端按物品的 {@code third_person} 配置选段并广播（所有人可见）；</li>
 *   <li><b>第一人称</b>：完全本地——只有物品配置了 {@code first_person} 才在本地起一段 FP 动画，
 *       不经过服务端，也不影响第三人称；没配置就保持原版第一人称。</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = net.minecraft.client.yiz.tizMod.MODID,
        bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class AnimClientEvents {

    /** 本地第一人称连段下标（与第三人称服务端下标互相独立）。 */
    private static final Map<UUID, Integer> FIRST_PERSON_COMBO = new ConcurrentHashMap<>();
    /** 触发节流：模组里 1.8 风格武器攻击间隔接近 0，按住攻击会每 tick 触发。 */
    private static final Map<UUID, Long> LAST_TRIGGER_MS = new ConcurrentHashMap<>();
    /** 与最小播放时长（0.3 秒）对齐。 */
    private static final long MIN_TRIGGER_GAP_MS = 300L;

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
        ItemStack held = player.getMainHandItem();
        boolean thirdPerson = AnimLibrary.hasThirdPerson(held);
        boolean firstPerson = AnimLibrary.hasFirstPerson(held);
        if (!thirdPerson && !firstPerson) {
            return;
        }
        // 无冷却武器（攻击间隔≈0）按住攻击时每 tick 都会触发，按动画最短时长节流；
        // 普通武器攻击间隔远大于 200ms，不会受影响。
        long now = System.currentTimeMillis();
        Long last = LAST_TRIGGER_MS.get(player.getUUID());
        if (last != null && now - last < MIN_TRIGGER_GAP_MS) {
            return;
        }
        LAST_TRIGGER_MS.put(player.getUUID(), now);
        if (firstPerson) {
            startFirstPerson(player, held);
        }
        if (thirdPerson) {
            C2SAnimAttackPayload.send();
        }
    }

    /** 第一人称动画只在本地播放，和第三人称的广播/播放队列完全独立。 */
    private static void startFirstPerson(LocalPlayer player, ItemStack held) {
        int combo = FIRST_PERSON_COMBO.getOrDefault(player.getUUID(), 0);
        AnimClip clip = AnimLibrary.firstPersonComboClip(held, combo);
        if (clip == null) {
            return;
        }
        FIRST_PERSON_COMBO.put(player.getUUID(), combo + 1);
        if (FIRST_PERSON_COMBO.size() > 512) {
            FIRST_PERSON_COMBO.clear();
        }
        // 和第三人称一致：播放时长 = 当前主手物品的攻击间隔
        float attackSeconds = (float) (player.getCurrentItemAttackStrengthDelay() / 20.0);
        if (attackSeconds < 0.05F) {
            attackSeconds = 0.05F;
        }
        AnimClientState.startFirstPerson(clip, attackSeconds);
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
            mc.setScreen(new net.minecraft.client.yiz.anim.editor.AnimItemSelectScreen());
        }
    }
}
