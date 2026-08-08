package net.minecraft.client.yiz.handler;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import java.util.UUID;

/**
 * 卢登激荡 overkill 捕获 — 玩家攻击时记录"伤害量 + 死前血量 + 攻击者 + 是否溅射"到目标 persistent data，
 * 供下游卢登在目标死亡时计算过量伤害并触发溅射。
 *
 * <p><b>捕获点</b>：{@code LivingEntityMixin.modifyHurtAmount} 末尾（amount 已含攻击方加成与
 * ARMOR/SPELL_DEFENSE 指数减免）。此时 {@code target.getHealth()} 仍为死前血量。overkill = amount − preHealth。</p>
 *
 * <p><b>连锁衰减</b>：executeSpill 施加溅射伤害前 {@link #setCurrentSpill} 标记当前溅射量，
 * captureIfLethal 检测到该标记则一并记录到 KEY_SPILL —— 下游 onLivingDeath 据此区分：
 * 有 KEY_SPILL = 死于溅射（连锁，新溅射量 = 记录值 × 衰减系数）；无 = 玩家直接击杀（首次，过量+6）。</p>
 *
 * <p>persistentData 仅服务端权威（不同步客户端），下游在服务端 onLivingDeath 读取。</p>
 */
public final class LudenOverkillHandler {

    private LudenOverkillHandler() {}

    private static final String KEY_AMOUNT = "yizmodqzk:luden_dmg";
    private static final String KEY_PRE    = "yizmodqzk:luden_pre";
    private static final String KEY_ATK    = "yizmodqzk:luden_atk";
    private static final String KEY_SPILL  = "yizmodqzk:luden_spill";

    /** 当前线程正在施加的溅射量（executeSpill 设 / captureIfLethal 读，区分直接攻击 vs 溅射）。null = 非溅射。 */
    private static final ThreadLocal<Float> CURRENT_SPILL = new ThreadLocal<>();

    public static void setCurrentSpill(float amount) { CURRENT_SPILL.set(amount); }
    public static void clearCurrentSpill() { CURRENT_SPILL.remove(); }
    /** 当前线程是否正在施加卢登溅射伤害（供 modifyHealthForVitalitySeverance 跳过减伤，全额扣血）。 */
    public static boolean isSpilling() { return CURRENT_SPILL.get() != null; }

    /**
     * 固定伤害直扣时记录溅射信息（绕过 hurt 的 captureIfLethal），供下游 onLivingDeath 连锁：
     * {@link #getSpillAmount} 用于衰减判定，{@link #getAttacker} 用于击杀者校验。
     */
    public static void recordSpill(LivingEntity target, float spillAmount, Player attacker) {
        var pd = target.getPersistentData();
        pd.putFloat(KEY_AMOUNT, spillAmount);      // 溅射量（作下跳 overkill 基数）
        pd.putFloat(KEY_PRE, target.getHealth());  // 死前血量
        pd.putFloat(KEY_SPILL, spillAmount);       // 标记为溅射伤害
        pd.putUUID(KEY_ATK, attacker.getUUID());
    }

    /**
     * hurt 层调用：玩家攻击（含溅射伤害）、且本次将致死（amount ≥ 当前血量）时记录候选。
     * 若处于溅射上下文（CURRENT_SPILL 已设），额外记录溅射量用于连锁衰减。
     */
    public static void captureIfLethal(LivingEntity target, DamageSource source, float amount) {
        if (target.level().isClientSide()) return;
        if (!(source.getEntity() instanceof Player player)) return;
        float preHealth = target.getHealth();
        if (preHealth <= 0 || amount < preHealth) return; // 非致死候选
        var pd = target.getPersistentData();
        pd.putFloat(KEY_AMOUNT, amount);
        pd.putFloat(KEY_PRE, preHealth);
        pd.putUUID(KEY_ATK, player.getUUID());
        Float spill = CURRENT_SPILL.get();
        if (spill != null) {
            pd.putFloat(KEY_SPILL, spill);   // 标记为溅射伤害 + 记录溅射量
        } else {
            pd.remove(KEY_SPILL);            // 玩家直接攻击：清除可能的残留标记
        }
    }

    /** 死亡时：读记录的攻击者 UUID。无记录则 null。 */
    public static UUID getAttacker(LivingEntity target) {
        var pd = target.getPersistentData();
        return pd.hasUUID(KEY_ATK) ? pd.getUUID(KEY_ATK) : null;
    }

    /** 死亡时：读 overkill = max(0, 伤害量 − 死前血量)。无记录则 0。 */
    public static float getOverkill(LivingEntity target) {
        var pd = target.getPersistentData();
        if (!pd.hasUUID(KEY_ATK)) return 0f;
        return Math.max(0f, pd.getFloat(KEY_AMOUNT) - pd.getFloat(KEY_PRE));
    }

    /** 死亡时：若死于溅射伤害，返回该次溅射量（减免前，用于连锁衰减）；否则返回 -1（玩家直接击杀）。 */
    public static float getSpillAmount(LivingEntity target) {
        var pd = target.getPersistentData();
        if (!pd.contains(KEY_SPILL)) return -1f;
        return pd.getFloat(KEY_SPILL);
    }

    /** 处理完清除记录。 */
    public static void clear(LivingEntity target) {
        var pd = target.getPersistentData();
        pd.remove(KEY_AMOUNT);
        pd.remove(KEY_PRE);
        pd.remove(KEY_ATK);
        pd.remove(KEY_SPILL);
    }
}
