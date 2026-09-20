package net.minecraft.client.yiz.mixin;

import net.minecraft.client.yiz.tool.health.HealthChannels;
import net.minecraft.client.yiz.tool.health.SecureHealthClosure;
import net.minecraft.client.yiz.tool.health.SyncedDataSupport;
import net.minecraft.network.syncher.EntityDataAccessor;
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
     * 通道读守卫（兜底防崩，泛化）：1.20.1 的通道 id 是「类池 + 类加载顺序」决定的全局可变状态，
     * 任意来源都可能把某个 id 的 DataItem 值写成别的类型 → vanilla {@code get()} 里
     * {@code Serializer.copy()} 抛 Byte→Integer / Float→Optional 等 ClassCastException
     * （AIR_SUPPLY、CUSTOM_NAME、第三方 EVASION_TIME 都中招）。此处：
     * <ul>
     *   <li>值类型与序列化器不匹配 → 就地修成类型安全默认值，并把该默认值直接返回（不再往下走）；</li>
     *   <li>该 id 根本没有 DataItem（例如冲突消解时丢弃了外来定义）→ 补一个类型安全的条目再返回，
     *       避免 vanilla 对 null 调 getValue() 抛 NPE。</li>
     * </ul>
     * 表字段用 {@link SyncedDataSupport} 缓存过的反射句柄读取（不依赖 @Shadow 的 SRG 名映射）。
     */
    @Inject(method = "get(Lnet/minecraft/network/syncher/EntityDataAccessor;)Ljava/lang/Object;",
            at = @At("HEAD"), cancellable = true)
    private <T> void yizmodqzk$guardTypedGet(EntityDataAccessor<T> key, CallbackInfoReturnable<T> cir) {
        try {
            var map = SyncedDataSupport.itemsById((SynchedEntityData) (Object) this);
            if (map == null) return;
            SynchedEntityData.DataItem<?> item = map.get(key.getId());
            if (item == null) {
                Object def = SyncedDataSupport.defaultFor(key.getSerializer(), null);
                if (def == null) return;
                SyncedDataSupport.putItem((SynchedEntityData) (Object) this, map, key, def);
                cir.setReturnValue((T) def);
                return;
            }
            Object def = SyncedDataSupport.defaultFor(key.getSerializer(), item.getValue());
            if (def == null) return;
            // 值类型与序列化器不匹配（被第三方按 id 写坏）→ 修复为类型安全默认值并直接返回
            ((SynchedEntityData.DataItem) item).setValue(def);
            cir.setReturnValue((T) def);
        } catch (Throwable ignored) {}
    }

    /**
     * 通道 id 撞车消解（防「实体构造失败 → 玩家被踢 / 客户端崩」）。
     *
     * <p>生产实测：某第三方 accessor 的 id 为 0（与 {@code Entity.DATA_SHARED_FLAGS_ID} 同槽）且被
     * define 进实体数据表 → 原版 {@code Entity.<init>} 首次 define 就抛
     * {@code Duplicate id value for 0!} → 实体构造失败（玩家登录「无效的玩家数据」、
     * 拾取粒子建假 ItemEntity 时崩客户端）。这里在 vanilla 抛异常之前接管：
     * 原版通道优先，驱逐外来占用者；否则丢弃这次定义、保留先到的条目。
     * 正常路径只多一次 map 查询（无冲突立即返回，零副作用）。</p>
     */
    @Inject(method = "define(Lnet/minecraft/network/syncher/EntityDataAccessor;Ljava/lang/Object;)V",
            at = @At("HEAD"), cancellable = true)
    private <T> void yizmodqzk$resolveDuplicateDefine(EntityDataAccessor<T> key, T value, CallbackInfo ci) {
        try {
            var map = SyncedDataSupport.itemsById((SynchedEntityData) (Object) this);
            if (map == null) return;
            SynchedEntityData.DataItem<?> item = map.get(key.getId());
            if (item == null) return;   // 正常路径
            EntityDataAccessor<?> existing = SyncedDataSupport.accessorOf(item);
            if (existing == key) {
                ci.cancel();            // 同一个 accessor 被 define 两次：保留第一次，不再抛
                return;
            }
            if (SyncedDataSupport.keepExisting((SynchedEntityData) (Object) this, key, existing)) {
                ci.cancel();            // 保留已有条目 → 丢弃这次定义（不再抛异常）
                return;
            }
            map.remove(key.getId());    // 驱逐外来占用者 → 让原版定义正常写入
        } catch (Throwable ignored) {}
    }

    // 类型安全默认值表已移到 net.minecraft.client.yiz.tool.health.SyncedDataSupport.defaultFor：
    // mixin 里的静态字段初始化会被合并进目标类 <clinit>，生产 SRG 环境引用原版字段会 NoSuchFieldError。
}
