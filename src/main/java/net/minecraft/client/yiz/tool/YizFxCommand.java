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
 * 闪电特效测试指令（排查用）— `/yiz fx` 对玩家面前目标发 S2C 触发闪电束/表面电弧。
 *
 * <p>效仿高版本直接调用特效：服务端发 {@code S2CShockFxPayload}（kind=1 链式电弧 + kind=0
 * 表面电弧），客户端 {@code LightningFX.spawnArc/spawnSurfaceArc} 渲染。用于定位：
 * 特效显示 = 特效 OK，问题在感电触发衔接；不显示 = 渲染/shader 问题。</p>
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
