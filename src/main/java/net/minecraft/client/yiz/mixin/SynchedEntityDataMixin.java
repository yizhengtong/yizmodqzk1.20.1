package net.minecraft.client.yiz.mixin;

import net.minecraft.client.yiz.tool.health.HealthChannels;
import net.minecraft.client.yiz.tool.health.SecureHealthClosure;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SynchedEntityData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * SynchedEntityData 数据层拦截（1.20.1，set 层拦截思路）。
 *
 * <p>目的：受保护实体（混淆血量存储）的 {@code SECURE_OBF}/{@code SECURE_OBF_KEY} 通道
 * 是<b>数据层</b>唯一真实血量写入点。外部若绕过 setHealth（走 {@code entityData.set} 直写串/
 * 改 key）即可破坏混淆血量 → 在 SynchedEntityData.set 层按「写门禁」拦截。</p>
 *
 * <p> <b>不依赖 {@code @Shadow entity} 字段</b>（生产 SRG 环境下字段名映射不命中会
 * {@code InvalidMixinException} FATAL 崩溃）：{@code SECURE_OBF}/{@code SECURE_OBF_KEY}
 * 通道 id 全局唯一（仅本模组 YizxianMob 定义），且客户端网络同步走 {@code assignValues}
 * （DataItem 直写，不经过 {@code set()}）→ 客户端不会经 set 写混淆串。故 handler 只需
 * 按 accessor id 匹配 + {@link SecureHealthClosure#isObfWriteAllowed()} 写门禁判断即可。</p>
 *
 * <p>写门禁（ThreadLocal）：家族写入口（setHealth/registerSecureHealth/onSyncedDataUpdated 回写）
 * 先 beginObfWrite() 再 set，外部无门禁 → cancel。 不用 {@code EntityAttributeGate.isCallerTrusted()}
 * （其引擎帧 SynchedEntityData.set 会被信任 → 外部直写也放行）。</p>
 *
 * <p>注入点：三参 {@code set(EntityDataAccessor, Object, boolean)}（两参委托它，拦到底层）。</p>
 */
@Mixin(value = SynchedEntityData.class, priority = Integer.MAX_VALUE)
public abstract class SynchedEntityDataMixin {

    /**
     * 拦截对混淆血量通道的写入：写门禁未开启（外部直写串 / 改 key）→ cancel。
     */
    @Inject(method = "set(Lnet/minecraft/network/syncher/EntityDataAccessor;Ljava/lang/Object;Z)V",
            at = @At("HEAD"), cancellable = true)
    private <T> void yizmodqzk$onSet(EntityDataAccessor<T> accessor, T value, boolean force, CallbackInfo ci) {
        int id = accessor.getId();
        boolean obf = id == HealthChannels.getSecureObf().getId();
        boolean key = id == HealthChannels.getSecureObfKey().getId();
        if ((obf || key) && !SecureHealthClosure.isObfWriteAllowed()) {
            ci.cancel();
        }
    }
}
