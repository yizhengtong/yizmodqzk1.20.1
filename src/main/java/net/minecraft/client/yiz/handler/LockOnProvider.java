package net.minecraft.client.yiz.handler;

import net.minecraft.client.Minecraft;
import net.minecraft.client.yiz.api.TargetFrameProvider;
import net.minecraft.client.yiz.attribute.YizAttributes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.RegistryObject;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 属性驱动锁定目标供应者（1.21.1 移植版）— 由 会心(HUIXIN)/渴攻(KEGONG) 属性驱动。
 * 客户端独立充能，视线射线命中第一个实体（穿墙），范围/充能时间来自玩家实体属性。
 *
 * <h3>默认互补值</h3>
 * <ul>
 *   <li>有会心无渴攻 → 渴攻默认 30 tick（1.5 秒）</li>
 *   <li>有渴攻无会心 → 会心默认 12 格</li>
 *   <li>两者均无 → 不激活（getTarget 返回 null）</li>
 * </ul>
 */
public class LockOnProvider implements TargetFrameProvider {

    private static final double DEFAULT_HUIXIN = 12.0;
    private static final double DEFAULT_KEGONG = 30.0;

    private static final ConcurrentHashMap<UUID, LockState> STATES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Long> LAST_FRAME = new ConcurrentHashMap<>();

    private record LockState(UUID targetUuid, int timer) {}

    @Override
    public Entity getTarget(Player player) {
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return null;

        // 读取属性，应用默认互补
        double rawHuixin = readAttr(player, YizAttributes.HUIXIN);
        double rawKegong = readAttr(player, YizAttributes.KEGONG);
        if (rawHuixin <= 0 && rawKegong <= 0) {
            STATES.remove(player.getUUID());
            LAST_FRAME.remove(player.getUUID());
            return null;
        }
        double range = rawHuixin > 0 ? rawHuixin : DEFAULT_HUIXIN;
        int chargeTicks = rawKegong > 0 ? (int) rawKegong : (int) DEFAULT_KEGONG;

        // 视线射线命中第一个实体（AABB 与射线最近交点，不检测方块=穿墙，配合穿墙描边）
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle();
        Vec3 end = eye.add(look.x * range, look.y * range, look.z * range);
        EntityHitResult hit = ProjectileUtil.getEntityHitResult(player, eye, end,
            player.getBoundingBox().inflate(range),
            e -> e instanceof LivingEntity && e != player && e.isAlive(),
            range);
        Entity best = hit != null ? hit.getEntity() : null;

        LockState state = STATES.get(player.getUUID());
        if (best == null) {
            STATES.remove(player.getUUID());
            LAST_FRAME.remove(player.getUUID());
            return null;
        }

        // 防止同帧多次调用（Manager + Renderer 各调一次）
        long frame = mc.level.getGameTime();
        Long prev = LAST_FRAME.get(player.getUUID());
        boolean sameFrame = prev != null && prev == frame;
        LAST_FRAME.put(player.getUUID(), frame);
        if (sameFrame) return best;

        int timer;
        if (state != null && state.targetUuid.equals(best.getUUID())) {
            timer = Math.min(state.timer + 1, chargeTicks);
        } else {
            timer = 1; // 换目标重新充能
        }
        STATES.put(player.getUUID(), new LockState(best.getUUID(), timer));
        return best;
    }

    @Override
    public float getCharge() {
        var mc = Minecraft.getInstance();
        if (mc.player == null) return 0;
        int chargeTicks = getChargeTicks(mc.player);
        if (chargeTicks <= 0) return 0;
        LockState state = STATES.get(mc.player.getUUID());
        return state != null ? (float) state.timer / chargeTicks : 0;
    }

    @Override
    public boolean isReady() {
        var mc = Minecraft.getInstance();
        if (mc.player == null) return false;
        int chargeTicks = getChargeTicks(mc.player);
        if (chargeTicks <= 0) return false;
        LockState state = STATES.get(mc.player.getUUID());
        return state != null && state.timer >= chargeTicks;
    }

    @Override
    public int getPriority() { return 10; }

    /** 始终使用默认纹理。 */
    @Override
    public ResourceLocation[] getCornerTextures() { return null; }

    /** 攻击后清除客户端状态。 */
    public static void reset(Player player) {
        STATES.remove(player.getUUID());
    }

    /** 获取充能时间（tick），含默认互补。 */
    private static int getChargeTicks(Player player) {
        double rawKegong = readAttr(player, YizAttributes.KEGONG);
        double rawHuixin = readAttr(player, YizAttributes.HUIXIN);
        if (rawHuixin <= 0 && rawKegong <= 0) return 0;
        return rawKegong > 0 ? (int) rawKegong : (int) DEFAULT_KEGONG;
    }

    private static double readAttr(Player player, RegistryObject<net.minecraft.world.entity.ai.attributes.Attribute> attr) {
        if (attr == null || !attr.isPresent()) return 0.0;
        var inst = player.getAttribute(attr.get());
        return inst != null ? inst.getValue() : 0.0;
    }
}
