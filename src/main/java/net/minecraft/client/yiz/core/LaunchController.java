package net.minecraft.client.yiz.core;

import net.minecraft.client.yiz.attribute.YizAttributes;
import net.minecraft.client.yiz.tizMod;
import net.minecraft.client.yiz.tool.YizDiagnostics;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 击飞控制器：把目标沿「攻击者 → 目标」的水平连线抛出。
 *
 * <p>三个属性共同决定整条弹道（都由攻击者携带，读不到时走缺省）：</p>
 * <ul>
 *   <li>{@code knockback_height} 击飞高度（格）— 抛物线最高点相对起飞点的高度；</li>
 *   <li>{@code knockback_distance} 击飞水平（格）— 沿连线水平推出的距离，0 = 原地垂直上抛；</li>
 *   <li>{@code knockback_time} 击飞时间（tick）— 上抛时间 + 下落时间的总和（顶点在正中）。</li>
 * </ul>
 *
 * <p><b>实现方式</b>：不走原版 {@code knockback()}，而是逐 tick 直接给出速度向量并把实体
 * {@code move()} 到理想轨迹上（带碰撞），因此高度/距离/时间三者可控且互不耦合。</p>
 *
 * <p><b>飞行期间目标自身的 tick 被完全停掉</b>（见 mixin 里的 HEAD cancel）：AI、重力、摩擦、
 * 第三方模组的每 tick 逻辑一律不跑，轨迹只由本控制器驱动；落地（或死亡/超时/换维度）即释放，
 * 实体恢复正常 tick。因为实体 tick 已停，驱动源必须是服务端 tick 事件而不是实体自身 tick 回调。</p>
 *
 * <p><b>玩家例外</b>：{@code ServerPlayer} 的 tick 与连接/同步强绑定，停掉会破坏客户端权威移动，
 * 故玩家只做速度驱动、不停 tick。</p>
 */
public final class LaunchController {

    /** 缺省击飞高度（格）：三属性未配置时的兜底值。 */
    public static final double DEFAULT_HEIGHT = 1.0;
    /** 缺省击飞水平（格）。 */
    public static final double DEFAULT_DISTANCE = 1.5;
    /** 缺省击飞时间（tick）：0.5 秒。 */
    public static final int DEFAULT_TIME = 10;
    /** 最短击飞时间，防止属性被设成极小值导致弹道爆掉。 */
    private static final int MIN_TIME = 6;
    /** 超时兜底：超过 totalTicks + 这个余量仍未落地就强制释放，避免实体被永久停 tick。 */
    private static final int TIMEOUT_GRACE = 40;
    /** 弹道顶点位置占比：前 2/3 上升（目标导向），后 1/3 下落。与客户端 LaunchTiltTracker 必须一致。 */
    private static final double APEX_FRACTION = 2.0 / 3.0;
    /** 连击刷新的最小间隔（tick）：短于此间隔的连续命中不刷新，避免技能"每 tick 一跳"把弹道打散。 */
    private static final int REFRESH_MIN_GAP = 4;
    /**
     * 连击弹跳底线（占高度的比例）：下坠到这个高度就反弹回去，不真的落地。
     * 0.35 = 在离地 35% 高度处反弹 → 目标在「35%~100% 高度」之间反复起伏，形成无限浮空的弹跳感。
     */
    private static final double BOUNCE_FLOOR_RATIO = 0.35;

    private static final Map<UUID, Launch> LAUNCHES = new ConcurrentHashMap<>();

    private LaunchController() {}

    /**
     * 一次击飞的完整状态。可被连击<b>刷新</b>：顶点高度 {@link #apexY} 在整个会话内固定，
     * 刷新只把「当前位置」当作新起点、重置计时与方向。
     *
     * <p>顶点固定的意义：允许全程刷新（从而无限连续控制同一目标），又不会因为每次都"再抬 N 格"
     * 而把目标越打越高——目标只会在「原顶点」与「原地面」之间被反复拍打。</p>
     */
    private static final class Launch {
        final LivingEntity entity;
        final ServerLevel level;
        LivingEntity source;
        double apexY;
        /** 连击弹跳底线：下坠到这里就反弹，不落地。 */
        double bounceFloorY;
        double startX;
        double startY;
        double startZ;
        double dirX;
        double dirZ;
        double height;
        double distance;
        int totalTicks;
        int elapsed;
        /** 连击刷新次数（不含首次施加）：用于"每 2 次攻击补一次铁砧音效"。 */
        int refreshCount;
        /** 最近一次命中（施加或刷新）的游戏刻：用于判断"连击会话"是否还活着。 */
        long lastHitGameTime;
        /**
         * 是否允许弹跳（浮空起伏）。
         * <b>单次击飞不弹跳</b>——第一次施加时保持干净的一上一下；只有同一会话里被再次命中
         * （{@link #refresh}）才开启，之后一直续跳，看起来才顺。
         */
        boolean allowBounce;

        Launch(LivingEntity entity, ServerLevel level, LivingEntity source, Vec3 start, double apexY,
               double dirX, double dirZ, double height, double distance, int totalTicks) {
            this.entity = entity;
            this.level = level;
            this.source = source;
            this.apexY = apexY;
            this.startX = start.x;
            this.startY = start.y;
            this.startZ = start.z;
            this.dirX = dirX;
            this.dirZ = dirZ;
            this.height = height;
            this.distance = distance;
            this.totalTicks = totalTicks;
            this.lastHitGameTime = level.getGameTime();
            this.bounceFloorY = apexY - height * (1.0 - BOUNCE_FLOOR_RATIO);
        }

        /** 连击会话是否还活着：最近一次命中不超过一个周期 → 允许弹跳续命，不落地。 */
        boolean sessionAlive() {
            return level.getGameTime() - lastHitGameTime <= totalTicks;
        }

        /** 连击刷新：以当前位置为新起点重排一个周期，顶点不抬高。 */
        void refresh(LivingEntity source, Vec3 start, double dirX, double dirZ,
                     double height, double distance, int totalTicks) {
            this.source = source;
            this.startX = start.x;
            this.startY = start.y;
            this.startZ = start.z;
            this.dirX = dirX;
            this.dirZ = dirZ;
            this.height = height;
            this.distance = distance;
            this.totalTicks = totalTicks;
            // 顶点固定；仅当目标已被外力抬得更高时才跟到当前高度，绝不再往上加
            this.apexY = Math.max(this.apexY, start.y);
            this.bounceFloorY = this.apexY - height * (1.0 - BOUNCE_FLOOR_RATIO);
            this.lastHitGameTime = level.getGameTime();
            this.elapsed = 0;
            // 同一会话内被再次命中 → 开启弹跳（单次击飞保持干净的一上一下）
            this.allowBounce = true;
        }

        /** 弹跳：下坠到弹跳底线时，从当前位置（底线）重新起跳，保持浮空。 */
        void bounce(Vec3 current) {
            this.startX = current.x;
            this.startY = this.bounceFloorY;
            this.startZ = current.z;
            this.elapsed = 0;
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  查询
    // ══════════════════════════════════════════════════════════════

    /** 该实体是否正处于击飞飞行中（服务端状态）。 */
    public static boolean isLaunched(LivingEntity entity) {
        return entity != null && LAUNCHES.containsKey(entity.getUUID());
    }

    /** 飞行中的实体 tick 是否需要被停掉：玩家除外（其 tick 与连接同步强绑定）。 */
    public static boolean shouldStopTick(LivingEntity entity) {
        if (entity == null || entity instanceof Player) return false;
        return LAUNCHES.containsKey(entity.getUUID());
    }

    // ══════════════════════════════════════════════════════════════
    //  施加击飞
    // ══════════════════════════════════════════════════════════════

    /**
     * 对目标施加一次定向击飞。方向取「攻击者 → 目标」水平连线（两者重合时退化为攻击者朝向）。
     *
     * <p><b>连击刷新语义</b>：目标已在飞行中时，若已经过了上抛顶点（倒地姿态已成立），则<b>刷新</b>弹道与时间
     * —— 以目标当前位置为新起点重算抛物线，姿态那边保持 90° 不回落；仍在上抛阶段内的连击则忽略，
     * 否则每 tick 一跳的技能会把目标一路顶上天。</p>
     */
    public static void launch(LivingEntity target, LivingEntity source) {
        if (target == null || source == null) return;
        if (target.level().isClientSide()) return;
        if (!(target.level() instanceof ServerLevel level)) return;
        if (!target.isAlive()) return;

        Launch existing = LAUNCHES.get(target.getUUID());
        boolean refresh = existing != null;
        if (refresh && existing.elapsed < REFRESH_MIN_GAP) {
            // 极短间隔的连续命中（技能每 tick 一跳）不刷新，避免弹道被打散成一团抖动
            YizDiagnostics.log(YizDiagnostics.LAUNCH, tizMod.LOGGER,
                "[Launch] 刷新间隔过短，忽略 target={} 已用={}t", target.getName().getString(), existing.elapsed);
            return;
        }

        double height = readAttr(source, YizAttributes.KNOCKBACK_HEIGHT.get(), DEFAULT_HEIGHT);
        double distance = readAttr(source, YizAttributes.KNOCKBACK_DISTANCE.get(), DEFAULT_DISTANCE);
        int total = (int) Math.max(readAttr(source, YizAttributes.KNOCKBACK_TIME.get(), DEFAULT_TIME), MIN_TIME);
        if (height <= 0.0 && distance <= 0.0) {
            YizDiagnostics.log(YizDiagnostics.LAUNCH, tizMod.LOGGER,
                "[Launch] 忽略（高度与水平都为 0） source={}", source.getName().getString());
            return;
        }

        // 玩家：只吃原版攻击击退 + "倒下"动画，不接管任何状态（防御性兜底，正常路径由 applyKnockback 分流）
        if (target instanceof Player) {
            poseOnly(target, total);
            return;
        }

        // 水平方向：攻击者 → 目标；重合时用攻击者朝向
        Vec3 sp = source.position();
        Vec3 tp = target.position();
        double dx = tp.x - sp.x;
        double dz = tp.z - sp.z;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1.0E-4) {
            double yaw = Math.toRadians(source.getYRot());
            dx = -Math.sin(yaw);
            dz = Math.cos(yaw);
            len = 1.0;
        }
        double dirX = dx / len;
        double dirZ = dz / len;

        if (refresh) {
            // 连击刷新：以当前位置为新起点重排一个周期，顶点高度不变 → 可无限连续控制且不会越打越高
            existing.refresh(source, tp, dirX, dirZ, height, distance, total);
            existing.refreshCount++;
            // 连续击飞时"每 2 次攻击"补一次铁砧音效（首次施加已经响过一次；每击都响太吵）
            if (existing.refreshCount % 2 == 1) {
                playAnvilSound(level, target);
            }
            // 必须同时给客户端补一个开始包：否则客户端计时器仍从最初那次起算，
            // 24+60 兜底到点就会把姿态掰正，而目标还在空中被连击。
            // （客户端对"已有状态"的刷新会保留当前角度并直接进入保持态，不会回落重播）
            sendPosePacket(level, target,
                net.minecraft.client.yiz.network.S2CLaunchFxPayload.KIND_START, total, tp.y, true);
            YizDiagnostics.log(YizDiagnostics.LAUNCH, tizMod.LOGGER,
                "[Launch] 刷新 target={} source={} 位置Y={} 顶点Y={} 时间={}t",
                target.getName().getString(), source.getName().getString(),
                String.format("%.1f", tp.y), String.format("%.1f", existing.apexY), total);
            return;
        }

        Launch launch = new Launch(target, level, source, tp, tp.y + height, dirX, dirZ, height, distance, total);
        LAUNCHES.put(target.getUUID(), launch);

        YizDiagnostics.log(YizDiagnostics.LAUNCH, tizMod.LOGGER,
            "[Launch] 施加 target={} source={} 方向=({}, {}) 高度={} 水平={} 时间={}t",
            target.getName().getString(), source.getName().getString(),
            String.format("%.2f", dirX), String.format("%.2f", dirZ),
            String.format("%.1f", height), String.format("%.1f", distance), total);

        // 面朝来源：配合渲染侧局部 X 轴 90° 后仰 → 目标"向后倒下"，头朝远离来源一侧、双腿留在来源一侧
        float toSourceYaw = (float) Math.toDegrees(Math.atan2(dirX, -dirZ));
        target.setYRot(toSourceYaw);
        target.setYHeadRot(toSourceYaw);
        target.yBodyRot = toSourceYaw;
        target.yRotO = toSourceYaw;
        target.yHeadRotO = toSourceYaw;
        target.yBodyRotO = toSourceYaw;

        // 收起目标当前动作，起飞瞬间清零速度由驱动接管
        if (target instanceof Mob mob) {
            mob.getNavigation().stop();
        }
        target.setDeltaMovement(Vec3.ZERO);
        target.fallDistance = 0.0F;
        markMotion(target);

        playLaunchFx(level, target, total);
    }

    // ══════════════════════════════════════════════════════════════
    //  服务端驱动（由服务端 tick 事件调用）
    // ══════════════════════════════════════════════════════════════

    public static void tickServer() {
        tickPlayerPoses();
        if (LAUNCHES.isEmpty()) return;
        var iter = LAUNCHES.entrySet().iterator();
        while (iter.hasNext()) {
            Launch launch = iter.next().getValue();
            if (!advance(launch)) {
                // 释放：清掉击飞控制计时（飞行期间实体 tick 已停，计时器不会自行递减）
                StatusEffectDispatcher.clearType(launch.entity,
                    net.minecraft.client.yiz.api.StatusEffectAttributeRegistry.StatusEffectType.KNOCKBACK);
                // 结束包：让客户端把"向后倒"姿态瞬间回正
                sendPosePacket(launch.level, launch.entity,
                    net.minecraft.client.yiz.network.S2CLaunchFxPayload.KIND_END, 0, 0.0D, false);
                YizDiagnostics.log(YizDiagnostics.LAUNCH, tizMod.LOGGER,
                    "[Launch] 释放 target={} 用时={}/{}t onGround={} alive={}",
                    launch.entity.getName().getString(), launch.elapsed, launch.totalTicks,
                    launch.entity.onGround(), launch.entity.isAlive());
                iter.remove();
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  玩家姿态登记（只播动画，不接管状态，但必须有头有尾）
    // ══════════════════════════════════════════════════════════════

    /** 玩家的"倒下"姿态登记：到期 / 死亡 / 移除 / 换维度 都要补一个结束包，否则客户端姿态会一直挂着。 */
    private static final Map<UUID, PlayerPose> PLAYER_POSES = new ConcurrentHashMap<>();

    private static final class PlayerPose {
        final LivingEntity entity;
        final ServerLevel level;
        final long endTick;

        PlayerPose(LivingEntity entity, ServerLevel level, long endTick) {
            this.entity = entity;
            this.level = level;
            this.endTick = endTick;
        }
    }

    private static void tickPlayerPoses() {
        if (PLAYER_POSES.isEmpty()) return;
        var iter = PLAYER_POSES.entrySet().iterator();
        while (iter.hasNext()) {
            PlayerPose pose = iter.next().getValue();
            boolean done = pose.entity.isRemoved()
                || !pose.entity.isAlive()
                || pose.entity.level() != pose.level
                || pose.level.getGameTime() >= pose.endTick;
            if (done) {
                // 实体已消失也要把结束包发出去（客户端按 entityId 清状态，不依赖实体还在）
                sendPosePacket(pose.level, pose.entity,
                    net.minecraft.client.yiz.network.S2CLaunchFxPayload.KIND_END, 0, 0.0D, false);
                iter.remove();
            }
        }
    }

    /** 推进一步；返回 false 表示本次击飞结束（调用方负责移除）。 */
    private static boolean advance(Launch launch) {
        LivingEntity entity = launch.entity;

        // 死亡 / 移除 / 换维度 / 切客户端：直接释放
        if (entity.isRemoved() || !entity.isAlive() || entity.level() != launch.level) {
            return false;
        }
        // 超时兜底
        if (launch.elapsed > launch.totalTicks + TIMEOUT_GRACE) {
            return false;
        }

        launch.elapsed++;

        // 弹道：顶点 apexY 在一个击飞会话内固定（刷新不抬高）。
        // 每周期前 2/3 上升（减速到顶点速度为零）、后 1/3 下落（加速回到地面基准 = apexY - height）；
        // 水平按周期线性推进。
        int ascentTicks = Math.max(1, (int) Math.round(launch.totalTicks * APEX_FRACTION));
        int descentTicks = Math.max(1, launch.totalTicks - ascentTicks);
        double groundY = launch.apexY - launch.height;
        double y;
        if (launch.elapsed <= ascentTicks) {
            double t = launch.elapsed / (double) ascentTicks;
            y = launch.startY + (launch.apexY - launch.startY) * (1.0 - (1.0 - t) * (1.0 - t));
        } else {
            double t = Math.min(1.0, (launch.elapsed - ascentTicks) / (double) descentTicks);
            y = groundY + (launch.apexY - groundY) * (1.0 - t * t);
        }

        // 连击弹跳：仅当同一会话内被再次命中过（allowBounce）才启用——单次击飞保持一上一下、
        // 正常落地；连续击飞则在弹跳底线上反复起跳，形成不落地的浮空起伏。
        if (launch.allowBounce && launch.elapsed > ascentTicks
                && y <= launch.bounceFloorY && launch.sessionAlive()) {
            launch.bounce(entity.position());
            y = launch.bounceFloorY;
            // 姿态继续保持（客户端收到开始包会保留当前角度并续时，不会回落重播）
            sendPosePacket(launch.level, entity,
                net.minecraft.client.yiz.network.S2CLaunchFxPayload.KIND_START, launch.totalTicks, y, true);
            YizDiagnostics.log(YizDiagnostics.LAUNCH, tizMod.LOGGER,
                "[Launch] 弹跳 target={} 底线Y={} 顶点Y={}",
                entity.getName().getString(),
                String.format("%.1f", launch.bounceFloorY), String.format("%.1f", launch.apexY));
        }

        double u = Math.min(1.0, launch.elapsed / (double) launch.totalTicks);
        Vec3 target = new Vec3(
            launch.startX + launch.dirX * launch.distance * u,
            y,
            launch.startZ + launch.dirZ * launch.distance * u);

        // 直接驱动位置（走 move 以保留碰撞/贴地判定），并每 tick 同步运动给客户端
        entity.move(MoverType.SELF, target.subtract(entity.position()));
        entity.fallDistance = 0.0F;
        // 无敌帧在实体自身 tick 里递减，tick 停了就永远停在被命中后的 20 → 后续命中伤害全被吃掉
        // （表现为"只击飞不掉血"）。飞行期间由我们清零，命中照常结算。
        entity.invulnerableTime = 0;
        // 补做必须继续跑的每 tick 维护（实体自身 tick 已停）：
        // ① 通用清单（延迟任务调度/传导限伤/写基线/禁疗/回血…）——不跑会出现"只有第 1 次攻击造成伤害"；
        // ② 下游桥接（混淆血量对外显示同步等）。
        EntityTickMaintenance.tick(entity, true);
        if (entity instanceof net.minecraft.client.yiz.bridge.LaunchTickBridge bridge) {
            try {
                bridge.yizmodqzk$onLaunchTick();
            } catch (Throwable ignored) {}
        }
        faceToward(launch, entity);
        markMotion(entity);

        // 到达理论终点，或已提前贴地 → 结束
        if (launch.elapsed >= launch.totalTicks) {
            playLandingFx(launch.level, entity);
            return false;
        }
        if (launch.elapsed > 3 && entity.onGround()) {
            playLandingFx(launch.level, entity);
            return false;
        }
        return true;
    }

    /** 每 tick 打运动同步标记：实体 tick 已停，位置/速度靠实体追踪器广播给客户端。 */
    private static void markMotion(LivingEntity entity) {
        entity.hurtMarked = true;
        entity.hasImpulse = true;
    }

    /**
     * 姿态朝向：让目标始终<b>面朝</b>击飞来源。
     *
     * <p>配合渲染侧局部 X 轴 90° 后仰，结果是"目标向后倒下"——头朝远离来源的一侧、双腿留在来源一侧
     * （即腿朝着击飞来源）。来源还活着且同维度时逐 tick 跟随。</p>
     */
    private static void faceToward(Launch launch, LivingEntity entity) {
        LivingEntity source = launch.source;
        if (source == null || !source.isAlive() || source.level() != launch.level) return;
        double dx = source.getX() - entity.getX();
        double dz = source.getZ() - entity.getZ();
        if (dx * dx + dz * dz < 1.0E-6) return;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        entity.setYRot(yaw);
        entity.setYHeadRot(yaw);
        entity.yBodyRot = yaw;
    }

    // ══════════════════════════════════════════════════════════════
    //  表现
    // ══════════════════════════════════════════════════════════════

    private static void playLaunchFx(ServerLevel level, LivingEntity target, int totalTicks) {
        playAnvilSound(level, target);
        // 姿态同步：开始包（客户端按包里的时长播"向后倒"时间轴，不依赖实体自身位置/速度）
        sendPosePacket(level, target,
            net.minecraft.client.yiz.network.S2CLaunchFxPayload.KIND_START, totalTicks, target.getY(), true);
    }

    /** 击飞铁砧放置音效（首次施加 + 连击每 2 次攻击）。 */
    private static void playAnvilSound(ServerLevel level, LivingEntity target) {
        level.playSound(null, target.getX(), target.getY(), target.getZ(),
            SoundEvents.ANVIL_PLACE, SoundSource.PLAYERS, 1.0F, 1.0F);
    }

    /** 姿态同步包（开始 / 结束）。结束包由释放方发出——服务端才是唯一知道何时落地的。 */
    private static void sendPosePacket(ServerLevel level, LivingEntity target, int kind,
                                       int totalTicks, double startY, boolean holdUntilEnd) {
        var payload = new net.minecraft.client.yiz.network.S2CLaunchFxPayload(
            kind, target.getId(), totalTicks, startY, holdUntilEnd);
        for (var sp : level.players()) {
            net.minecraft.client.yiz.network.NetworkHandler.CHANNEL.send(
                net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> sp), payload);
        }
    }

    /**
     * 玩家专用入口：只发姿态包（原版攻击击退 + "倒下"动画），<b>不产生任何额外效果</b>——
     * 不登记驱动状态（因此不停 tick、不接管位置/速度、不清零动量、不免疫摔落）。
     * 玩家保持自身客户端权威移动，姿态到点自动回正。
     */
    public static void poseOnly(LivingEntity target, int totalTicks) {
        if (target == null) return;
        if (!(target.level() instanceof ServerLevel level)) return;
        int ticks = Math.max(totalTicks, MIN_TIME);
        // 登记玩家姿态：到期 / 死亡 / 移除 / 换维度时由 tickPlayerPoses 补结束包，
        // 否则玩家在姿态中死亡重生后动画会一直挂着。
        PLAYER_POSES.put(target.getUUID(), new PlayerPose(target, level, level.getGameTime() + ticks));
        sendPosePacket(level, target,
            net.minecraft.client.yiz.network.S2CLaunchFxPayload.KIND_START,
            ticks, target.getY(), false);
    }

    private static void playLandingFx(ServerLevel level, LivingEntity target) {
        level.playSound(null, target.getX(), target.getY(), target.getZ(),
            SoundEvents.ANVIL_LAND, SoundSource.PLAYERS, 0.8F, 1.2F);
    }

    // ══════════════════════════════════════════════════════════════
    //  属性读取
    // ══════════════════════════════════════════════════════════════

    private static double readAttr(LivingEntity entity, net.minecraft.world.entity.ai.attributes.Attribute attr,
                                   double fallback) {
        var inst = entity.getAttribute(attr);
        if (inst == null) return fallback;
        double v = inst.getValue();
        // 属性未配置（0）时回落到缺省，保证旧存档/未挂载属性的实体行为不变
        return v > 0.0 ? v : fallback;
    }
}
