package net.minecraft.client.yiz.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.yiz.handler.MultiJumpTracker;
import net.minecraft.client.yiz.network.C2SMultiJumpPayload;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.WeakHashMap;

/**
 * 多段跳客户端分发 Mixin — 消费 {@code YizAttributes.JUMP_COUNT}（仅本地玩家，1.21.1 移植版）。
 *
 * <p>注入 {@link LocalPlayer#aiStep} HEAD：每次物理按下跳跃键（{@code keyJump.consumeClick()}），
 * 若玩家在空中且还有多段跳次数 → 消耗一次，用 JUMP_HEIGHT 反解 Y 初速设置 deltaMovement，
 * 发 C2S 请求服务端权威消耗。</p>
 *
 * <p>1.20.1 差异：无 PlayerDataAPI S2C 同步，客户端本地维护剩余次数（乐观）——
 * 落地（onGround 上升沿）recharge 到满值、每次跳 -1、内置 CD 防长按。服务端独立权威兜底。</p>
 */
@Mixin(LocalPlayer.class)
public abstract class ClientMultiJumpMixin {

    /** 多段跳内置 CD（客户端本地，按 LocalPlayer 实例记录）。 */
    @Unique
    private static final WeakHashMap<LocalPlayer, Integer> JUMP_CD = new WeakHashMap<>();

    /** 上一 tick 是否在地面（检测落地上升沿以 recharge）。 */
    @Unique
    private static final WeakHashMap<LocalPlayer, Boolean> WAS_ON_GROUND = new WeakHashMap<>();

    @Inject(method = "aiStep", at = @At("HEAD"))
    private void yizmodqzk$onJumpClick(CallbackInfo ci) {
        LocalPlayer self = (LocalPlayer) (Object) this;

        // 落地上升沿：客户端本地 recharge（无 S2C 同步，本地维护剩余）
        boolean nowGround = self.onGround();
        boolean wasGround = WAS_ON_GROUND.getOrDefault(self, false);
        WAS_ON_GROUND.put(self, nowGround);
        if (nowGround && !wasGround) {
            MultiJumpTracker.recharge(self);
        }

        // 先递减本 tick 的多段跳 CD
        int cd = JUMP_CD.getOrDefault(self, 0);
        if (cd > 0) JUMP_CD.put(self, cd - 1);

        // 点按一次 = 一次跳跃意图
        if (!Minecraft.getInstance().options.keyJump.consumeClick()) return;

        // 落地按下跳跃键由原版走 jumpFromGround 起跳；本 Mixin 只处理"空中再跳"
        if (self.onGround()) return;
        if (self.isFallFlying()) return;
        if (self.isInWater()) return;
        if (self.isPassenger()) return;
        if (self.hasEffect(net.minecraft.world.effect.MobEffects.LEVITATION)) return;

        if (JUMP_CD.getOrDefault(self, 0) > 0) return;

        if (!MultiJumpTracker.hasJump(self)) return;

        // 乐观预测：JUMP_HEIGHT 反解 Y 初速；服务端权威消耗纠正
        int height = MultiJumpTracker.jumpHeight(self);
        double vy = MultiJumpTracker.velocityFromHeight(height);
        self.setDeltaMovement(self.getDeltaMovement().x, vy, self.getDeltaMovement().z);
        self.hurtMarked = true;
        MultiJumpTracker.setRemaining(self, MultiJumpTracker.getRemaining(self) - 1);
        C2SMultiJumpPayload.send();

        JUMP_CD.put(self, MultiJumpTracker.JUMP_COOLDOWN_TICKS);
    }
}
