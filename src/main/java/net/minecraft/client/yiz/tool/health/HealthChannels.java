package net.minecraft.client.yiz.tool.health;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.LivingEntity;

/**
 * 健康值 delta 通道定义（独立 holder 类）。
 *
 * <p><b>为什么独立成类：</b>若把 {@code EntityDataAccessor} 定义为 Mixin 的 {@code @Unique static}
 * 字段并用 {@code EntityDataSerializers.FLOAT} 初始化，Mixin 会把该初始化代码合并进目标类
 * {@code LivingEntity.<clinit>}——在生产环境（SRG 运行时名）下该 vanilla 字段引用不会被正确重映射，
 * 导致 {@code NoSuchFieldError: EntityDataSerializers does not have member field FLOAT} 在 Bootstrap 期崩溃。
 * 改放本类：<code>EntityDataSerializers.FLOAT</code> 引用由本模组自己的 reobf 正确映射，且懒加载不
 * 污染任何 vanilla 类的静态初始化。</p>
 *
 * <p> {@link SynchedEntityData#defineId} 需在实体 {@code SynchedEntityData} 捕获该通道前调用；
 * 本类首次被 {@code defineSynchedData} 注入引用时触发初始化，晚于 Bootstrap、早于任何实体构建，安全。</p>
 */
public final class HealthChannels {

    private HealthChannels() {}

    /** 健康增量通道：有效血量上限 = maxHealth + delta（delta ≤ 0）。 */
    public static final EntityDataAccessor<Float> DELTA_HEALTH =
        SynchedEntityData.defineId(LivingEntity.class, EntityDataSerializers.FLOAT);

    //  混淆血量存储（per-entity DataParameter）
    // 真实血量存混淆 String，key 存 INT（每实体随机，随实体存档+同步，同实体读写恒定）。
    // 只由本模组受保护实体（YizxianMob，SECURE_PULSE>0）define/读写。

    /** 混淆血量（String）：FloatObf.enc(health, key)。 */
    public static final EntityDataAccessor<String> SECURE_OBF =
        SynchedEntityData.defineId(LivingEntity.class, EntityDataSerializers.STRING);

    /** 每实体混淆 key（INT）：随机，随实体存档 + 同步客户端。 */
    public static final EntityDataAccessor<Integer> SECURE_OBF_KEY =
        SynchedEntityData.defineId(LivingEntity.class, EntityDataSerializers.INT);
}
