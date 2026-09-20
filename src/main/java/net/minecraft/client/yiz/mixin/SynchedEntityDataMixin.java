package net.minecraft.client.yiz.mixin;

import net.minecraft.client.yiz.tool.health.HealthChannels;
import net.minecraft.client.yiz.tool.health.SecureHealthClosure;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializer;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

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

    /**
     * 数值通道读守卫（兜底防崩，泛化）：1.20.1 按类分配 DataParameter id + 多模组按 id 直写，
     * 任意来源可能把 INT/LONG/FLOAT 通道的 DataItem 值写成 Byte 等错误类型 → vanilla
     * {@code get()} 的 {@code Serializer.copy()} 抛 Byte→Integer 等 ClassCastException（AIR_SUPPLY、
     * 第三方模组 EVASION_TIME 等都中招）。此处任何数值序列化器读取时检测，值类型与序列化器
     * 不匹配即修复为类型默认值，避免整个游戏崩溃。用 {@link MixinAccess} 反射读 itemsById
     * （不依赖 @Shadow/@Accessor 的 SRG 字段名映射，生产安全）。
     */
    @Inject(method = "get(Lnet/minecraft/network/syncher/EntityDataAccessor;)Ljava/lang/Object;",
            at = @At("HEAD"))
    private <T> void yizmodqzk$guardTypedGet(EntityDataAccessor<T> key, CallbackInfoReturnable<T> cir) {
        try {
            var ser = key.getSerializer();
            it.unimi.dsi.fastutil.ints.Int2ObjectMap<SynchedEntityData.DataItem<?>> map =
                net.minecraft.client.yiz.util.MixinAccess.field(this, SynchedEntityData.class,
                    it.unimi.dsi.fastutil.ints.Int2ObjectMap.class, 0);
            if (map == null) return;
            SynchedEntityData.DataItem<?> item = map.get(key.getId());
            if (item == null) return;
            Object def = yizmodqzk$mismatchDefault(ser, item.getValue());
            if (def == null) return;
            // 值类型与序列化器不匹配（被第三方按 id 写坏）→ 修复为类型安全默认值
            ((SynchedEntityData.DataItem) item).setValue(def);
            item.setDirty(true);
        } catch (Throwable ignored) {}
    }

    /**
     * 返回类型不匹配时应写入的兜底值；返回 {@code null} 表示"无需修复"（类型正常或该序列化器不守卫）。
     *
     * <p>为什么必须覆盖对象类型：生产崩溃 06:19 是第三方实体类从 0 开始 defineId、父链未注册，
     * 于是自己的通道抢占了原版 {@code Entity} 的 id 1/2，读 {@code getCustomName()}
     * （OPTIONAL_COMPONENT 通道）时拿到 Float 直接 ClassCastException 崩渲染线程。</p>
     */
    private static Object yizmodqzk$mismatchDefault(EntityDataSerializer<?> ser, Object v) {
        // 基础类型
        if (ser == EntityDataSerializers.INT) return v instanceof Integer ? null : (Object) 0;
        if (ser == EntityDataSerializers.LONG) return v instanceof Long ? null : (Object) 0L;
        if (ser == EntityDataSerializers.FLOAT) return v instanceof Float ? null : (Object) 0.0F;
        if (ser == EntityDataSerializers.BYTE) return v instanceof Byte ? null : (Object) (byte) 0;
        if (ser == EntityDataSerializers.BOOLEAN) return v instanceof Boolean ? null : (Object) false;
        if (ser == EntityDataSerializers.STRING) return v instanceof String ? null : (Object) "";
        // 对象类型（读取端最后一道防线）
        if (ser == EntityDataSerializers.COMPONENT)
            return v instanceof net.minecraft.network.chat.Component ? null : net.minecraft.network.chat.Component.empty();
        if (ser == EntityDataSerializers.OPTIONAL_COMPONENT || ser == EntityDataSerializers.OPTIONAL_BLOCK_STATE
                || ser == EntityDataSerializers.OPTIONAL_BLOCK_POS || ser == EntityDataSerializers.OPTIONAL_UUID
                || ser == EntityDataSerializers.OPTIONAL_GLOBAL_POS)
            return v instanceof java.util.Optional ? null : java.util.Optional.empty();
        if (ser == EntityDataSerializers.ITEM_STACK)
            return v instanceof net.minecraft.world.item.ItemStack ? null : net.minecraft.world.item.ItemStack.EMPTY;
        if (ser == EntityDataSerializers.BLOCK_STATE)
            return v instanceof net.minecraft.world.level.block.state.BlockState ? null
                    : net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        if (ser == EntityDataSerializers.BLOCK_POS)
            return v instanceof net.minecraft.core.BlockPos ? null : net.minecraft.core.BlockPos.ZERO;
        if (ser == EntityDataSerializers.DIRECTION)
            return v instanceof net.minecraft.core.Direction ? null : net.minecraft.core.Direction.NORTH;
        if (ser == EntityDataSerializers.COMPOUND_TAG)
            return v instanceof net.minecraft.nbt.CompoundTag ? null : new net.minecraft.nbt.CompoundTag();
        return null;
    }
}
