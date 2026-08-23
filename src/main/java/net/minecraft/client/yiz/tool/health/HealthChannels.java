package net.minecraft.client.yiz.tool.health;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.LivingEntity;

/**
 * 健康值 delta 通道定义（独立 holder 类）。
 *
 * <p><b>为什么独立成类：</b>若把 EntityDataAccessor 定义为 Mixin 的 @Unique static 字段并用
 * EntityDataSerializers.FLOAT 初始化，Mixin 会把该初始化合并进目标类 LivingEntity.&lt;clinit&gt;，
 * 生产环境（SRG 运行时名）vanilla 字段引用不会被正确重映射 → NoSuchFieldError。改放本类，
 * EntityDataSerializers.FLOAT 由本模组 reobf 正确映射。</p>
 *
 * <p><b>为什么懒加载（holder + getter）：</b>若用顶层 public static final 字段，SynchedEntityDataMixin
 * 的 set 方法里 GETSTATIC 引用 SECURE_OBF 会在 mixin 应用阶段（class transformation 字节码链接）触发
 * 本类 &lt;clinit&gt; → defineId 抢占 vanilla Entity 的 DataParameter id 0 → 后续实体（ItemEntity 等）构造
 * 报 Duplicate id value for 0 崩溃。改 holder + getter：defineId 延迟到首次真正访问（首实体
 * defineSynchedData 后，此时 vanilla Entity 已 defineId），得到正确 id。</p>
 */
public final class HealthChannels {

    private HealthChannels() {}

    private static final class Holder {
        static final EntityDataAccessor<Float> DELTA_HEALTH =
            SynchedEntityData.defineId(LivingEntity.class, EntityDataSerializers.FLOAT);
        static final EntityDataAccessor<String> SECURE_OBF =
            SynchedEntityData.defineId(LivingEntity.class, EntityDataSerializers.STRING);
        static final EntityDataAccessor<Integer> SECURE_OBF_KEY =
            SynchedEntityData.defineId(LivingEntity.class, EntityDataSerializers.INT);
    }

    /** 健康增量通道：有效血量上限 = maxHealth + delta（delta ≤ 0）。 */
    public static EntityDataAccessor<Float> getDeltaHealth() { return Holder.DELTA_HEALTH; }

    /** 混淆血量（String）：FloatObf.enc(health, key)。 */
    public static EntityDataAccessor<String> getSecureObf() { return Holder.SECURE_OBF; }

    /** 每实体混淆 key（INT）：随机，随实体存档 + 同步客户端。 */
    public static EntityDataAccessor<Integer> getSecureObfKey() { return Holder.SECURE_OBF_KEY; }
}
