package net.minecraft.client.yiz.tool;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.PacketDistributor;

import java.util.List;

/**
 * 闪电特效测试指令（排查用）— {@code /yiz fx} 对玩家面前目标发 S2C 触发闪电束 + 感电围弧。
 *
 * <p>效仿高版本直接调用特效：服务端发 {@code S2CShockFxPayload}（kind=1 链式电弧 +
 * kind=0 感电围弧）。kind=0 走的是与"命中即感电"完全相同的那条链：
 * {@code ShockEffects.shock(entity, ticks)} → 身体外那一圈空间里的短弧群
 * （{@code ShockArcs}，<b>编号 4，默认开</b>）。对<b>自己</b>发的那一条（duration=100 tick）是
 * "围弧长什么样"最快的验证手段。</p>
 *
 * <p><b>围弧跟着模型走</b>：弧的几何是<b>独立的三维折线</b>（飘在体外那一圈空间里），
 * 但两个端点取自<b>实体自己被渲染时写出的那批顶点</b>里采出来的锚点 ⇒
 * 走动/转身/被击飞倒下/被别的模组把模型整体挪走，弧全都在模型上
 * （详见 {@code ShockArcs} 的类注释：为什么"独立几何 + 顶点锚点"两头的好处都拿到了）。</p>
 *
 * <p><b>注意去重窗口</b>：kind=0 在客户端有"同一实体 / 同一格"两级去重（{@code SURFACE_ARC_EXPIRY}
 * 与 {@code CELL_SURFACE_EXPIRY}），刚被感电过再敲本指令可能整条被跳过 —— 等 0.5s 以上再敲，
 * 或先走开一格。</p>
 */
public final class YizFxCommand {

    private YizFxCommand() {}

    public static void register() {
        SimpleCommandRegistry.register(
            Commands.literal("yiz")
                .then(Commands.literal("fx")
                    .executes(ctx -> {
                        CommandSourceStack src = ctx.getSource();
                        if (src.getEntity() instanceof ServerPlayer player) {
                            triggerFx(player);
                        }
                        return 1;
                    })));
    }

    /** 对玩家面前目标发 S2C 闪电特效（链式电弧 + 表面电弧）。 */
    private static void triggerFx(ServerPlayer player) {
        Entity target = raycastTarget(player, 16);
        net.minecraft.client.yiz.network.NetworkHandler.CHANNEL.send(
            PacketDistributor.PLAYER.with(() -> player),
            new net.minecraft.client.yiz.network.S2CShockFxPayload(
                1, player.getId(), 0, target != null ? List.of(target.getId()) : List.of()));
        // 表面电弧（kind=0）：玩家自身带电发光
        net.minecraft.client.yiz.network.NetworkHandler.CHANNEL.send(
            PacketDistributor.PLAYER.with(() -> player),
            new net.minecraft.client.yiz.network.S2CShockFxPayload(0, player.getId(), 100, List.of()));
    }

    /** 玩家视线方向最近可拾取实体。 */
    private static Entity raycastTarget(ServerPlayer player, double range) {
        Vec3 eye = player.getEyePosition(1f);
        Vec3 view = player.getViewVector(1f);
        AABB search = new AABB(eye, eye.add(view.scale(range))).inflate(1.0);
        return player.level().getEntities(player, search, e -> e.isAlive() && e.isPickable()).stream()
            .filter(e -> {
                Vec3 toE = e.position().subtract(eye);
                return toE.lengthSqr() > 1e-4 && toE.normalize().dot(view) > 0.9;
            })
            .min(java.util.Comparator.comparingDouble(e -> e.distanceToSqr(eye)))
            .orElse(null);
    }
}
