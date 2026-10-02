package net.minecraft.client.yiz.tool;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.client.yiz.core.PlayerIdentityStore;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code /yiz player} —— 玩家身份管理（离线模式改名/换号导致 UUID 变化时，别丢数据）。
 *
 * <ul>
 *   <li>{@code list}：所有玩家记录（UUID / 名字历史 / 物品配置条数 / 个体开关 / 有无令牌）；</li>
 *   <li>{@code pending}：待批准的认领申请；</li>
 *   <li>{@code approve <序号>} / {@code reject <序号>}：<b>服主</b>批准或拒绝认领；</li>
 *   <li>{@code migrate <目标> <来源>}：<b>服主</b>手工合并（目标可以是 UUID 或名字）；</li>
 *   <li>{@code token <玩家>}：<b>服主</b>签发/轮换一次性认领令牌（私发给本人）；</li>
 *   <li>{@code claim <令牌>}：玩家自己执行 → 只生成一条待批准申请。</li>
 * </ul>
 *
 * <p>信任边界：身份只以服务端观察到的 UUID/名字为准；令牌只是"发起认领"的凭据，
 * 批准权在服主，且全程写审计（{@code players.json} 的 {@code migrations}）。</p>
 */
public final class YizPlayerCommand {

    private YizPlayerCommand() {}

    private static final SuggestionProvider<CommandSourceStack> PLAYERS = (ctx, builder) -> {
        List<String> names = new ArrayList<>();
        try {
            for (var p : ctx.getSource().getServer().getPlayerList().getPlayers()) {
                names.add(p.getGameProfile().getName());
            }
        } catch (Throwable ignored) {}
        try {
            for (String line : PlayerIdentityStore.listLines()) {
                String first = line.split(" ")[0];
                if (!names.contains(first)) names.add(first);
            }
        } catch (Throwable ignored) {}
        return SharedSuggestionProvider.suggest(names, builder);
    };

    public static void register() {
        LiteralArgumentBuilder<CommandSourceStack> cmd = Commands.literal("yiz")
            .then(Commands.literal("player")
                .then(Commands.literal("list").executes(YizPlayerCommand::list))
                .then(Commands.literal("pending")
                    .requires(s -> s.hasPermission(2))
                    .executes(YizPlayerCommand::pending))
                .then(Commands.literal("approve")
                    .requires(s -> s.hasPermission(4))
                    .then(Commands.argument("index", IntegerArgumentType.integer(0))
                        .executes(ctx -> decide(ctx, true))))
                .then(Commands.literal("reject")
                    .requires(s -> s.hasPermission(4))
                    .then(Commands.argument("index", IntegerArgumentType.integer(0))
                        .executes(ctx -> decide(ctx, false))))
                .then(Commands.literal("migrate")
                    .requires(s -> s.hasPermission(4))
                    .then(Commands.argument("target", StringArgumentType.word()).suggests(PLAYERS)
                        .then(Commands.argument("source", StringArgumentType.word()).suggests(PLAYERS)
                            .executes(YizPlayerCommand::migrate))))
                .then(Commands.literal("token")
                    .requires(s -> s.hasPermission(4))
                    .then(Commands.argument("player", StringArgumentType.word()).suggests(PLAYERS)
                        .executes(YizPlayerCommand::token)))
                .then(Commands.literal("claim")
                    .then(Commands.argument("token", StringArgumentType.word())
                        .executes(YizPlayerCommand::claim))));
        SimpleCommandRegistry.register(cmd);
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        var lines = PlayerIdentityStore.listLines();
        var src = ctx.getSource();
        src.sendSuccess(() -> Component.literal("§6玩家身份记录（" + lines.size() + " 条）："), false);
        for (String l : lines) src.sendSuccess(() -> Component.literal("§7- §f" + l), false);
        src.sendSuccess(() -> Component.literal(
            "§7改名丢配置时：§f/yiz player migrate <目标> <来源>§7；换号改名用 §f/yiz player token <玩家>"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int pending(CommandContext<CommandSourceStack> ctx) {
        var lines = PlayerIdentityStore.pendingLines();
        var src = ctx.getSource();
        if (lines.isEmpty()) {
            src.sendSuccess(() -> Component.literal("§7没有待批准的认领申请"), false);
            return Command.SINGLE_SUCCESS;
        }
        src.sendSuccess(() -> Component.literal("§6待批准的认领申请："), false);
        for (String l : lines) src.sendSuccess(() -> Component.literal("§f" + l), false);
        src.sendSuccess(() -> Component.literal("§7批准：§f/yiz player approve <序号>§7；拒绝：§f/yiz player reject <序号>"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int decide(CommandContext<CommandSourceStack> ctx, boolean approve) {
        int idx = IntegerArgumentType.getInteger(ctx, "index");
        String by = ctx.getSource().getTextName();
        String msg = approve ? PlayerIdentityStore.approve(idx, by) : PlayerIdentityStore.reject(idx);
        if (approve && msg.startsWith("已批准")) {
            ctx.getSource().sendSuccess(() -> Component.literal("§a" + msg), true);
        } else if (!approve && msg.startsWith("已拒绝")) {
            ctx.getSource().sendSuccess(() -> Component.literal("§e" + msg), true);
        } else {
            ctx.getSource().sendFailure(Component.literal("§c" + msg));
            return 0;
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int migrate(CommandContext<CommandSourceStack> ctx) {
        String target = StringArgumentType.getString(ctx, "target");
        String source = StringArgumentType.getString(ctx, "source");
        String msg = PlayerIdentityStore.migrate(target, source, ctx.getSource().getTextName());
        if (msg.startsWith("已合并")) {
            ctx.getSource().sendSuccess(() -> Component.literal("§a" + msg), true);
            return Command.SINGLE_SUCCESS;
        }
        ctx.getSource().sendFailure(Component.literal("§c" + msg));
        return 0;
    }

    private static int token(CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "player");
        String id = PlayerIdentityStore.resolveId(name);
        if (id == null) {
            ctx.getSource().sendFailure(Component.literal("§c找不到玩家 " + name + " 的身份记录"));
            return 0;
        }
        String token = PlayerIdentityStore.issueToken(id);
        if (token == null) {
            ctx.getSource().sendFailure(Component.literal("§c签发失败"));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(
            "§a已为 " + name + " 签发一次性认领令牌：§f" + token
            + "\n§7私发给本人让其在换号后执行 §f/yiz player claim " + token
            + "§7；之后你用 §f/yiz player approve <序号>§7 批准"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int claim(CommandContext<CommandSourceStack> ctx) {
        String token = StringArgumentType.getString(ctx, "token");
        var player = ctx.getSource().getPlayer();
        if (player == null) {
            ctx.getSource().sendFailure(Component.literal("§c该指令需要玩家身份执行"));
            return 0;
        }
        String msg = PlayerIdentityStore.claim(token, player);
        ctx.getSource().sendSuccess(() -> Component.literal("§a" + msg), false);
        return Command.SINGLE_SUCCESS;
    }
}
