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
 * 感电视觉 S2C 事件包（1.20.1 移植版，record→class + SimpleChannel）。
 *
 * <p>两种事件（{@link #kind}）：
 * <ul>
 *   <li><b>0 = APPLY</b>：实体体表电弧 + surface 着色器叠加（带去重），持续 = durationTicks</li>
 *   <li><b>1 = BURST</b>：center→targetIds 链式闪电电弧（短寿命）。</li>
 * </ul>
 *
 * <p>1.20.1 差异：{@code LightningFX.spawnSurfaceArc/spawnArc} 的电弧几何渲染尚未移植
 * （见 port-gap-list.md #16，D4 闪电最小子集延后），本包 handle 只维护 {@link ShockedEntityAPI}
 * 感电状态（实体带电发光由 LivingEntityRendererSurfaceLightningMixin 渲染）；电弧几何后续补。</p>
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
                SURFACE_ARC_EXPIRY.values().removeIf(exp -> exp < nowMs0);
                CELL_SURFACE_EXPIRY.values().removeIf(exp -> exp < nowMs0);
                ARC_PAIR_EXPIRY.values().removeIf(exp -> exp < nowMs0);
            }

            float lifeSec = payload.durationTicks / 20f;
            if (payload.kind == 0) {
                // APPLY：体表电流去重 + 注册感电状态 + 表面缠绕电弧（LightningFX.spawnSurfaceArc）
                Long expiry = SURFACE_ARC_EXPIRY.get(payload.centerId);
                long nowMs = System.currentTimeMillis();
                if (expiry != null && nowMs < expiry) return;
                SURFACE_ARC_EXPIRY.put(payload.centerId, nowMs + (long) (lifeSec * 1000));

                Entity target = mc.level.getEntity(payload.centerId);
                if (target == null) return;
                // 同格去重：多个实体同格只渲染一份体表电流
                long cell = cellKey(target.getX(), target.getY(), target.getZ());
                Long cellExp = CELL_SURFACE_EXPIRY.get(cell);
                if (cellExp != null && nowMs < cellExp) return;
                CELL_SURFACE_EXPIRY.put(cell, nowMs + 500L);

                net.minecraft.client.yiz.lightning.LightningFX.spawnSurfaceArc(
                    target, Math.max(lifeSec, 1f), 0.028f, 0.4f, 0.6f, 1.0f);
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

    /** 实体 ID → 体表电弧过期时间戳（ms），防重复堆积。 */
    private static final ConcurrentHashMap<Integer, Long> SURFACE_ARC_EXPIRY = new ConcurrentHashMap<>();
    /** 格子坐标(long) → 体表电弧过期时间戳，同格多实体只渲染一份。 */
    private static final ConcurrentHashMap<Long, Long> CELL_SURFACE_EXPIRY = new ConcurrentHashMap<>();
    /** (fromId,toId) 无序配对 → 链式电弧过期时间戳，防密集 AoE 同一段电弧重复叠加。 */
    private static final ConcurrentHashMap<Long, Long> ARC_PAIR_EXPIRY = new ConcurrentHashMap<>();
    /** 链式电弧配对去重窗口（ms），与 BURST 电弧寿命 0.5s 一致。 */
    private static final long ARC_PAIR_EXPIRY_MS = 500L;
    private static long lastCleanupMs = 0L;

    private static long cellKey(double x, double y, double z) {
        int ix = (int) Math.floor(x), iy = (int) Math.floor(y), iz = (int) Math.floor(z);
        return ((long)ix << 42) ^ ((long)iy << 21) ^ (long)iz;
    }

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
