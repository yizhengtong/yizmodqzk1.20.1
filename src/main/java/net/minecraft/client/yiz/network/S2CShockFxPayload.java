package net.minecraft.client.yiz.network;

import net.minecraft.client.Minecraft;
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
 *   <li><b>0 = APPLY</b>：实体围弧（编号 4，带去重），持续 = durationTicks；</li>
 *   <li><b>1 = BURST</b>：center→targetIds 链式闪电电弧（编号 1，短寿命，空气里的电弧）。</li>
 * </ul>
 *
 * <p><b>围弧的生产者就是本类 kind=0</b>：{@code ShockEffects.shock(target, 感电tick)} —— 只登记
 * "这个实体在感电"这一个状态；几何由 {@code ShockArcs} 负责：<b>独立的三维短弧</b>
 * （飘在身体外 0.05~0.35 格那一圈空间里、有自己的顶点），端点取自实体模型顶点采出的锚点
 * （{@code ShockAnchors}）⇒ <b>模型被渲染到哪里，弧就在哪里</b>
 * （别的模组把模型挪到 10 格外，弧照样跟着飞）。</p>
 *
 * <p><b>历史（已删除）</b>：kind=0 曾经还兼着"贴模型游离弧"的生产者（原编号 3，走模型几何镜像的
 * {@code AIR_ARC} 层）。用户实测那一层<b>一开（有实体感电时）就整屏发黑</b>（与更早的
 * {@code LIGHTNING} 层、原编号 2 同一症状）⇒ 层、状态表、着色器、以及本类里那段调用全部删除；
 * 黑屏根因与"别再加回镜像层"的结论写在 {@code LightningRenderer} 的类注释里。
 * 同一批清理里 {@code ShockedEntityAPI}（感电状态机，唯一消费者是已删除的原编号 5 旧体表叠亮）
 * 也一并删除，所以本类不再有任何"状态注册"的副作用 —— 只做登记围弧/生成链式电弧两件事。</p>
 *
 * <p>第四/五轮曾把编号 4 做成镜像管线的一层（{@code SHOCK} / {@code rendertype_shock_surface}，
 * 几何 = 模型顶点本身）。它没黑屏，但用户实测否掉了观感：
 * <b>"效果就是幽蓝色纹理贴在表面"</b> —— 只要几何是皮肤顶点，"哪里发光"就由"哪块皮肤可见"决定，
 * 片元里怎么往外取样都改不了这一点。第六轮因此把几何换成独立折线、只把顶点当锚点，
 * 并<b>删掉了那一层与它的三个着色器资源</b>（见 {@code ModelMirrorLayers} 与 {@code ShockArcs}）。
 * 旧的 {@code SurfaceArcEffect}（自己攒世界包围盒上的短线段 + 渲染时复算姿态）更早已整体删除。</p>
 *
 * <p><b>开关（{@code /yiz fxtoggle}，见 {@code FxDebugSwitches}）</b>：</p>
 * <ul>
 *   <li>kind=0 → <b>开关 4</b>（{@code FxDebugSwitches.surfaceArcs()}，<b>默认开</b>，
 *       门就在 {@code ShockEffects.shock(...)} 那一句外面）；</li>
 *   <li>kind=1 → <b>开关 1</b>（{@code FxDebugSwitches.chainArc()}，门在下面的分支开头）。</li>
 * </ul>
 */
public class S2CShockFxPayload {

    final int kind;               // 0=APPLY(围弧), 1=BURST(链式电弧)
    final int centerId;           // entity.getId()：APPLY=目标实体; BURST=电弧起点
    final int durationTicks;      // APPLY=围弧持续tick; BURST=未使用
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
                // APPLY：围弧去重（同一实体 / 同一格两级）+ 登记实体围弧
                Long expiry = SURFACE_ARC_EXPIRY.get(payload.centerId);
                long nowMs = System.currentTimeMillis();
                if (expiry != null && nowMs < expiry) return;
                SURFACE_ARC_EXPIRY.put(payload.centerId, nowMs + (long) (lifeSec * 1000));

                Entity target = mc.level.getEntity(payload.centerId);
                if (target == null) return;
                // 同格去重：多个实体同格只渲染一份围弧
                long cell = cellKey(target.getX(), target.getY(), target.getZ());
                Long cellExp = CELL_SURFACE_EXPIRY.get(cell);
                if (cellExp != null && nowMs < cellExp) return;
                CELL_SURFACE_EXPIRY.put(cell, nowMs + 500L);

                // ── 围弧（编号 4，<b>默认开</b>）────────────────────────────────────
                // 只登记"这个实体在感电"这一个状态；几何由 ShockArcs 负责
                // （独立的三维短弧 + 端点取自模型顶点采出的锚点 ⇒ 模型被渲染到哪里，弧就在哪里）。
                // 关掉时已登记的状态由 ShockEffects.clear()（+ ShockArcs.clear()）清掉，见 FxDebugSwitches.toggle。
                if (net.minecraft.client.yiz.tool.FxDebugSwitches.surfaceArcs()) {
                    net.minecraft.client.yiz.lightning.fx.ShockEffects.shock(
                            target, Math.max(1, Math.round(lifeSec * 20f)), 1.0f);
                }
            } else {
                // BURST：链式电弧 — 逐段连接（高版本逻辑）：targetIds 逐段 spawnArc，最后连回中心
                // 端点用 PositionSupplier.following 实时插值，跟随移动目标不滞后；
                // 每段按 (fromId,toId) 配对去重，防密集 AoE 同一段电弧重复叠加
                // ── 开关 1（闪电链）：生成入口直接不进（/yiz fxtoggle 1）──
                // 只跳过"电弧"这一半，两项互不影响（开关 1 的另一半入口在 LightningFX.spawnArc 的两个重载里）。
                if (!net.minecraft.client.yiz.tool.FxDebugSwitches.chainArc()) return;
                Entity center = mc.level.getEntity(payload.centerId);
                if (center == null) return;
                net.minecraft.client.yiz.lightning.orchestrate.PositionSupplier prev = null;
                int prevId = -1;
                for (int id : payload.targetIds) {
                    Entity t = mc.level.getEntity(id);
                    if (!(t instanceof net.minecraft.world.entity.LivingEntity tle)) continue;
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

    /** 实体 ID → 围弧过期时间戳（ms），防重复堆积。 */
    private static final ConcurrentHashMap<Integer, Long> SURFACE_ARC_EXPIRY = new ConcurrentHashMap<>();
    /** 格子坐标(long) → 围弧过期时间戳，同格多实体只渲染一份。 */
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
