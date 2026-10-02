package net.minecraft.client.yiz.network;

import net.minecraft.client.Minecraft;
import net.minecraft.client.yiz.api.ShockedEntityAPI;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 感电视觉 S2C 事件包（record→class + SimpleChannel）。
 *
 * <p>两种事件（{@link #kind}）：
 * <ul>
 *   <li><b>0 = APPLY</b>：注册"这个实体正在感电"（持续时间 = durationTicks）→ 体表闪电由
 *       {@code LightningSurfaceLayer}（模型几何镜像的 LIGHTNING 层，几何 = 实体模型本身）画出来；</li>
 *   <li><b>1 = BURST</b>：center→targetIds 链式闪电电弧（短寿命，空气里的电弧）。</li>
 * </ul>
 *
 * <p><b>分工</b>：<b>挂在实体身上的弧</b>（体表持续电流 + 命中瞬间游离弧）一律走模型几何镜像
 * （几何 = 实体模型顶点双写，贴四肢/头部/盔甲轮廓）；只有<b>真正在空气里</b>的弧
 * （两点之间的链式 / 球状闪电表面）才用 {@code LightningFX.spawnArc/spawnSurfaceArcOnBall}。
 * kind=0 因此<b>不再</b>生成按包围盒算的 {@code SurfaceArcEffect} —— 那是"围着一个方盒子跳的电"，
 * 本来就不贴模型；kind=1（链式）保留 {@code spawnArc}。</p>
 */
public class S2CShockFxPayload {

    final int kind;               // 0=APPLY(体表电流), 1=BURST(链式电弧)
    final int centerId;           // entity.getId()：APPLY=目标实体; BURST=电弧起点
    final int durationTicks;      // APPLY=体表持续tick; BURST=未使用
    final List<Integer> targetIds; // BURST=电弧终点实体列表; APPLY=空

    public S2CShockFxPayload(int kind, int centerId, int durationTicks, List<Integer> targetIds) {
        this.kind = kind;
        this.centerId = centerId;
        this.durationTicks = durationTicks;
        this.targetIds = targetIds;
    }

    public static void encode(S2CShockFxPayload payload, FriendlyByteBuf buf) {
        buf.writeVarInt(payload.kind);
        buf.writeVarInt(payload.centerId);
        buf.writeVarInt(payload.durationTicks);
        buf.writeVarInt(payload.targetIds.size());
        for (int id : payload.targetIds) buf.writeVarInt(id);
    }

    public static S2CShockFxPayload decode(FriendlyByteBuf buf) {
        int kind = buf.readVarInt();
        int centerId = buf.readVarInt();
        int durationTicks = buf.readVarInt();
        int n = buf.readVarInt();
        List<Integer> targetIds = new ArrayList<>(n);
        for (int i = 0; i < n; i++) targetIds.add(buf.readVarInt());
        return new S2CShockFxPayload(kind, centerId, durationTicks, targetIds);
    }

    public static void handle(S2CShockFxPayload payload, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            var mc = Minecraft.getInstance();
            if (mc.level == null) return;

            // 每 500ms 清理一次过期条目，防内存泄漏
            long nowMs0 = System.currentTimeMillis();
            if (nowMs0 - lastCleanupMs > 500L) {
                lastCleanupMs = nowMs0;
                ARC_PAIR_EXPIRY.values().removeIf(exp -> exp < nowMs0);
            }

            if (payload.kind == 0) {
                // ── 两类闪电的分工（用户两次反馈之后的定案：「体表跳跃闪电没贴合模型」+「空气电弧也要跟着模型轮廓」）──
                // ① 挂在实体身上的弧，全部走模型几何镜像（几何 = 实体模型双写，贴四肢/头部/盔甲轮廓）：
                //    · LIGHTNING 层（LightningSurfaceLayer）＝ 持续感电的体表电流，由本分支注册的状态驱动；
                //    · AIR_ARC 层（LightningAirArcLayer）＝ 命中瞬间的短寿命游离弧，由 LightningFX.spawnSurfaceArc 注册。
                // ② 真正在空气里的弧 = LightningRenderer.ARCS（链式，两点折线）与
                //    spawnSurfaceArcOnBall（球状闪电表面；球不是实体，没有模型可镜像）。
                // 历史上 kind=0 会生成 SurfaceArcEffect（按<b>包围盒六个面</b>撒弧、还会跨 40~80% 面宽），
                // 那正是"一圈围着脸的方盒子电"——已彻底不再用于实体。
                ShockedEntityAPI.putClient(payload.centerId, payload.durationTicks);
            } else {
                // BURST：链式电弧 — 逐段连接（高版本逻辑）：targetIds 逐段 spawnArc，最后连回中心
                // 端点用 PositionSupplier.following 实时插值，跟随移动目标不滞后；
                // 每段按 (fromId,toId) 配对去重，防密集 AoE 同一段电弧重复叠加
                ShockedEntityAPI.putClient(payload.centerId, payload.durationTicks > 0 ? payload.durationTicks : 20);
                Entity center = mc.level.getEntity(payload.centerId);
                if (center == null) return;
                net.minecraft.client.yiz.lightning.orchestrate.PositionSupplier prev = null;
                int prevId = -1;
                for (int id : payload.targetIds) {
                    Entity t = mc.level.getEntity(id);
                    if (!(t instanceof net.minecraft.world.entity.LivingEntity tle)) continue;
                    ShockedEntityAPI.putClient(id, payload.durationTicks > 0 ? payload.durationTicks : 20);
                    net.minecraft.client.yiz.lightning.orchestrate.PositionSupplier pos =
                        net.minecraft.client.yiz.lightning.orchestrate.PositionSupplier.offset(
                            net.minecraft.client.yiz.lightning.orchestrate.PositionSupplier.following(tle),
                            0, tle.getBbHeight() * 0.5, 0);
                    if (prev != null && !isArcDuplicate(prevId, id)) {
                        net.minecraft.client.yiz.lightning.LightningFX.spawnArc(
                            prev, pos, 0.5f, 0.05f, 0.4f, 0.6f, 1.0f);
                    }
                    prev = pos;
                    prevId = id;
                }
                // 最后一条：最近实体 → 中心，汇聚收束
                if (prev != null && !isArcDuplicate(prevId, payload.centerId)) {
                    net.minecraft.client.yiz.lightning.orchestrate.PositionSupplier centerPos =
                        net.minecraft.client.yiz.lightning.orchestrate.PositionSupplier.offset(
                            net.minecraft.client.yiz.lightning.orchestrate.PositionSupplier.following(center),
                            0, center.getBbHeight() * 0.5, 0);
                    net.minecraft.client.yiz.lightning.LightningFX.spawnArc(
                        prev, centerPos, 0.5f, 0.05f, 0.4f, 0.6f, 1.0f);
                }
            }
        });
        ctx.get().setPacketHandled(true);
    }

    /** (fromId,toId) 无序配对 → 链式电弧过期时间戳，防密集 AoE 同一段电弧重复叠加。 */
    private static final ConcurrentHashMap<Long, Long> ARC_PAIR_EXPIRY = new ConcurrentHashMap<>();
    /** 链式电弧配对去重窗口（ms），与 BURST 电弧寿命 0.5s 一致。 */
    private static final long ARC_PAIR_EXPIRY_MS = 500L;
    private static long lastCleanupMs = 0L;

    /** 两个实体 id 编码成无序配对 key（(a,b) 与 (b,a) 同 key）。 */
    private static long pairKey(int a, int b) {
        long lo = Math.min(a, b);
        long hi = Math.max(a, b);
        return (lo << 32) | (hi & 0xFFFFFFFFL);
    }

    /** 配对是否在去重窗口内已 spawn 过；未 spawn 则记录并返回 false。 */
    private static boolean isArcDuplicate(int fromId, int toId) {
        long key = pairKey(fromId, toId);
        long nowMs = System.currentTimeMillis();
        Long exp = ARC_PAIR_EXPIRY.get(key);
        if (exp != null && nowMs < exp) return true;
        ARC_PAIR_EXPIRY.put(key, nowMs + ARC_PAIR_EXPIRY_MS);
        return false;
    }
}
