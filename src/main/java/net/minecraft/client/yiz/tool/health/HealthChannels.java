package net.minecraft.client.yiz.tool.health;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.LivingEntity;

/**
 * 健康值 delta / 混淆血量通道定义（独立 holder 类）。
 *
 * <p><b>为什么独立成类：</b>若把 EntityDataAccessor 定义为 Mixin 的 @Unique static 字段并用
 * EntityDataSerializers.FLOAT 初始化，Mixin 会把该初始化合并进目标类 LivingEntity.&lt;clinit&gt;，
 * 生产环境（SRG 运行时名）vanilla 字段引用不会被正确重映射 → NoSuchFieldError。改放本类，
 * EntityDataSerializers.FLOAT 由本模组 reobf 正确映射。</p>
 *
 * <p><b>为什么手动固定 id（254/253/252）而非 defineId 自动分配：</b>1.20.1 的
 * {@code SynchedEntityData.defineId} 按类池 + 子类继承分配 id，多模组环境下无论何时触发
 * （mixin 应用期 / 实体构造 / FML 初始化），通道 id 都会参与 LivingEntity/Player 池计数 →
 * 与 vanilla/第三方通道极易撞车（实测 Duplicate id value for 0 / for 38 崩溃，实体无法生成）。
 * 手动固定 id 不走 defineId、不参与 id 池计数，客户端/服务端一致，实体 define 不再冲突。
 * 254 是合法上限（&lt;255），远离自动分配范围（模组自动 defineId 几乎不会到 254）。</p>
 */
public final class HealthChannels {

    private HealthChannels() {}

    /** 健康增量通道（id 254）：有效血量上限 = maxHealth + delta（delta ≤ 0）。 */
    public static final EntityDataAccessor<Float> DELTA_HEALTH =
        new EntityDataAccessor<>(254, EntityDataSerializers.FLOAT);

    /** 混淆血量 String 通道（id 253）：FloatObf.enc(health, key)。 */
    public static final EntityDataAccessor<String> SECURE_OBF =
        new EntityDataAccessor<>(253, EntityDataSerializers.STRING);

    /** 每实体混淆 key INT 通道（id 252）：随机，随实体存档 + 同步客户端。 */
    public static final EntityDataAccessor<Integer> SECURE_OBF_KEY =
        new EntityDataAccessor<>(252, EntityDataSerializers.INT);

    public static EntityDataAccessor<Float> getDeltaHealth() { return DELTA_HEALTH; }
    public static EntityDataAccessor<String> getSecureObf() { return SECURE_OBF; }
    public static EntityDataAccessor<Integer> getSecureObfKey() { return SECURE_OBF_KEY; }
}
