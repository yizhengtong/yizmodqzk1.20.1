package net.minecraft.client.yiz.mixin;

import net.minecraft.client.yiz.tizMod;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializer;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * SynchedEntityData 通道 id 撞车兜底（1.20.1 特有问题的全局防线）。
 *
 * <p><b>问题</b>：1.20.1 的 {@code SynchedEntityData.defineId} 按「类池 + 继承链」分配 id，
 * 且是<b>全局可变状态</b>。只要有模组（或 mixin）在 vanilla 的 {@code Entity.&lt;clinit&gt;} 之前
 * 抢先分配，它就会拿到 id 0；随后任何实体构造时 {@code Entity.&lt;init&gt;} 定义
 * {@code DATA_SHARED_FLAGS_ID}（id 0）就抛：</p>
 * <pre>
 * java.lang.IllegalArgumentException: Duplicate id value for 0!
 *   at SynchedEntityData.define → Entity.&lt;init&gt;
 *   → Couldn't place player in world → lost connection: 无效的玩家数据
 * </pre>
 * <p>症状极易被误判为"玩家存档损坏/数据丢失"——实际是玩家实体根本构造不出来，登录被踢。</p>
 *
 * <p><b>兜底</b>：在 {@code defineId} 返回处检查，凡拿到 id 0 的通道一律改分配到保留段
 * （{@link #RESERVED_ID_START} 起，远离自动分配范围——自动分配几乎不会爬到 200 以上，
 * 本模组 {@code HealthChannels} 占用 252-254）。这样无论哪个模组抢了 0，都不会再炸实体构造。</p>
 */
@Mixin(SynchedEntityData.class)
public abstract class SynchedEntityDataDefineIdMixin {

    /** 保留段起始 id（含）。 */
    private static final int RESERVED_ID_START = 200;
    /** 保留段长度（252-254 留给 HealthChannels）。 */
    private static final int RESERVED_ID_RANGE = 52;

    /** 已告警过的类，避免刷屏。 */
    private static final java.util.Set<String> WARNED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 低 id（1/2，原版 Entity 保留区）抢占的告警去重。 */
    private static final java.util.Set<String> LOW_ID_WARNED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    @Inject(method = "defineId", at = @At("RETURN"), cancellable = true)
    private static <T> void yizmodqzk$avoidIdZero(Class<? extends Entity> entityClass,
                                                  EntityDataSerializer<T> serializer,
                                                  CallbackInfoReturnable<EntityDataAccessor<T>> cir) {
        EntityDataAccessor<T> accessor = cir.getReturnValue();
        if (accessor == null) return;
        // 诊断：非 Entity 的类拿到 id 1/2 就是「本类通道与 Entity 的 DATA_AIR_SUPPLY/DATA_CUSTOM_NAME
        // 同槽」。这类通道在客户端/服务端**分配不一致**时，同步包会把别的类型写进同 id 槽
        // （生产崩溃 06:24：getCustomName() 读出 Float → ClassCastException）。
        // 只记录不改动：id 0 有确定性的重映射方案，1/2 若重映射会挪动原版槽位语义。
        if (entityClass != null && entityClass != Entity.class && accessor.getId() < 3
                && LOW_ID_WARNED.add(entityClass.getName() + "#" + accessor.getId())) {
            tizMod.LOGGER.warn("[SynchedEntityData] {} 的通道被分配到 id {}（原版 Entity 的保留区，"
                    + "调用方={}）→ 与 DATA_AIR_SUPPLY/DATA_CUSTOM_NAME 同槽，跨端 id 漂移时会导致类型错乱崩溃；"
                    + "序列化器={}",
                entityClass.getName(), accessor.getId(),
                yizmodqzk$callerClass(), serializer.getClass().getName());
        }
        if (accessor.getId() != 0) return;
        // ⚠️ 绝不能动 vanilla 自己的 DATA_SHARED_FLAGS_ID：它本来就是 Entity 池的第一个 id（合法 id 0）。
        // 第一版没排除它，把原版共享标志通道重映射走了 → SynchedEntityData.get 拿不到 DataItem → NPE。
        //
        // 但「跳过 Entity.class」有一个漏洞（生产 id 0 撞车的最后一条通路）：**第三方类替 Entity.class
        // 调 defineId**。若该调用发生在 Entity.<clinit> 之前（Entity 还没进池），走「池里没有 → j=0」
        // 分支就返回 id 0，并且 pool[Entity] 被写成 0 → 随后原版自己的 8 个通道全部 +1 偏移
        // （flags 变 1、air 变 2…）。而它自己的 id 0 一旦也被 define 进实体，就与后来的原版通道同槽
        // → `Duplicate id value for 0!` → 实体构造失败（玩家登录被踢）。
        // 判据：调用方不是 net.minecraft.* 就是外来替调 → 重映射。调用方未知/原版 → 一律保留（保守）。
        String caller = yizmodqzk$callerClass();
        boolean foreignCaller = caller != null && !caller.startsWith("net.minecraft.");
        if (entityClass == null) return;
        if (entityClass == Entity.class && !foreignCaller) return;

        // 只有「别的类也抢到了 id 0」才是真撞车（会导致 Entity.<init> 定义共享标志时抛
        // Duplicate id value for 0 → 玩家实体建不出来 → 登录被踢「无效的玩家数据」）。
        // 用类名哈希做**确定性**映射：客户端/服务端算出来的 id 必须一致，否则端间通道错位。
        int id = RESERVED_ID_START + Math.floorMod(entityClass.getName().hashCode(), RESERVED_ID_RANGE);
        cir.setReturnValue(new EntityDataAccessor<>(id, serializer));
        if (WARNED.add(entityClass.getName() + "#" + caller)) {
            tizMod.LOGGER.warn("[SynchedEntityData] 检测到 {} 替 {} 抢占了通道 id 0（原版共享标志槽）"
                    + "→ 已把该通道改分配到 id {}（否则实体构造会抛 Duplicate id value for 0，"
                    + "玩家实体建不出来、登录被踢「无效的玩家数据」）",
                caller == null ? "?" : caller, entityClass.getName(), id);
        }
    }

    /**
     * 栈上第一个「非原版、非本模组、非 Mixin 框架」的调用方类名。
     *
     * <p>用来判断这次 {@code defineId} 是原版自己调的，还是第三方替别的类调的——这是 id 0 撞车的
     * 关键判据（第三方替 {@code Entity.class} 在 {@code Entity.<clinit>} 之前调，就会拿到 id 0，
     * 并把原版 8 个通道整体顶偏一位）。defineId 只在类初始化时调用，栈扫描成本可忽略。</p>
     */
    private static String yizmodqzk$callerClass() {
        try {
            for (StackTraceElement e : new Throwable().getStackTrace()) {
                String n = e.getClassName();
                if (n.startsWith("java.") || n.startsWith("jdk.") || n.startsWith("sun.")
                        || n.startsWith("org.spongepowered.")
                        || n.startsWith("net.minecraft.client.yiz.")
                        || n.equals("net.minecraft.network.syncher.SynchedEntityData")) {
                    continue;
                }
                return n;
            }
        } catch (Throwable ignored) {}
        return null;
    }
}
