package net.minecraft.client.yiz.mixin;

import net.minecraft.client.yiz.attribute.YizAttributes;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.RegistryObject;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.WeakHashMap;

/**
 * 移动属性 Mixin — 消费 {@link YizAttributes#MOVE_SPEED} / {@link YizAttributes#MAX_RUN_SPEED}
 * / {@link YizAttributes#AIR_SPEED}（1.21.1 移植版）。
 *
 * <p>语义与 yizxian {@code MixinMovementAttributes} 完全对齐，数据源从 EffectTag 改为
 * yizmodqzk 原生属性：
 * <ul>
 *   <li>MOVE_SPEED/MAX_RUN_SPEED → 注入 {@link Player#getSpeed()} RETURN，
 *       walkSpeed × (1 + movePct/100)，疾跑时按 MAX_RUN_SPEED ramp 叠加</li>
 *   <li>AIR_SPEED → 注入 {@code travel()} HEAD 降低空中水平摩擦，
 *       增加跳跃水平位移</li>
 * </ul>
 *
 * <p>所有属性值语义均为百分比（50 = +50%）。</p>
 */
@Mixin(Player.class)
public abstract class PlayerMovementMixin {

    private static final WeakHashMap<Player, Integer> SPRINT_TICKS = new WeakHashMap<>();
    private static final int SPRINT_RAMP_TICKS = 60;
    private static final double DEFAULT_SPRINT_BONUS = 0.50;

    /** 读 yizmodqzk 属性值（不存在返回 0）。1.20.1：RegistryObject → .get()。 */
    private static double readAttr(Player player, RegistryObject<Attribute> attr) {
        if (attr == null || !attr.isPresent()) return 0;
        AttributeInstance inst = player.getAttribute(attr.get());
        return inst != null ? inst.getValue() : 0;
    }

    /** MOVE_SPEED + MAX_RUN_SPEED：改写 getSpeed() 返回值。 */
    @Inject(method = "getSpeed", at = @At("RETURN"), cancellable = true)
    private void yizmodqzk$applyMovementAttrs(CallbackInfoReturnable<Float> cir) {
        Player player = (Player) (Object) this;
        double movePct = readAttr(player, YizAttributes.MOVE_SPEED);
        double runPct = readAttr(player, YizAttributes.MAX_RUN_SPEED);
        if (movePct == 0 && runPct == 0) return;

        float walkSpeed = cir.getReturnValue() * (float) (1.0 + movePct / 100.0);

        boolean sprinting = player.isSprinting() && player.onGround();
        int ticks = sprinting ? SPRINT_TICKS.getOrDefault(player, 0) + 1 : 0;
        SPRINT_TICKS.put(player, ticks);
        double sprintMult = 1.0;
        if (sprinting && ticks > 0) {
            double max = DEFAULT_SPRINT_BONUS + runPct / 100.0;
            sprintMult = 1.0 + max * Math.min((double) ticks / SPRINT_RAMP_TICKS, 1.0);
        }

        cir.setReturnValue(walkSpeed * (float) sprintMult);
    }

    /**
     * AIR_SPEED：降低空中水平摩擦力，增加跳跃水平距离。
     * <p>反解目标摩擦 {@code f' = 1 - 0.09 / (1 + airPct×2/100)}（2× 倍率修正），
     * 在 travel() HEAD 预乘 {@code f'/0.91}，travel 内 ×0.91 后净摩擦 = f'。</p>
     */
    @Inject(method = "travel", at = @At("HEAD"))
    private void yizmodqzk$applyAirSpeed(Vec3 travelVector, CallbackInfo ci) {
        Player player = (Player) (Object) this;
        if (player.onGround() || player.isFallFlying()) return;
        double airPct = readAttr(player, YizAttributes.AIR_SPEED);
        if (airPct <= 0) return;
        double desiredFriction = 1.0 - 0.09 / (1.0 + airPct * 2.0 / 100.0);
        double preFactor = desiredFriction / 0.91;
        Vec3 dm = player.getDeltaMovement();
        player.setDeltaMovement(dm.x * preFactor, dm.y, dm.z * preFactor);
    }
}
