package net.minecraft.client.yiz.tool;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.client.yiz.core.YizConfig;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code /yiz config} —— 模组配置的查看与临时修改。
 *
 * <ul>
 *   <li>{@code list}：列出全部配置项、当前值与来源；</li>
 *   <li>{@code get <键>}：查一项；</li>
 *   <li>{@code set <键> <值>}：<b>只影响本次游戏</b>（会话覆盖，关掉即恢复）；</li>
 *   <li>{@code save}：把本次会话的改动<b>写回 TOML</b>（永久生效）；</li>
 *   <li>{@code reset <键>}：撤销该项的会话改动；</li>
 *   <li>{@code reload}：重新读 TOML 并立即生效（手改文件后不必重启）；</li>
 *   <li>{@code where <键>}：这一项的值来自哪一层（默认 / TOML / 会话）。</li>
 * </ul>
 */
public final class YizConfigCommand {

    private YizConfigCommand() {}

    /** 键名补全。 */
    private static final SuggestionProvider<CommandSourceStack> KEYS = (ctx, builder) -> {
        List<String> keys = new ArrayList<>();
        for (YizConfig.Entry e : YizConfig.entries()) keys.add(e.key());
        return SharedSuggestionProvider.suggest(keys, builder);
    };

    /** 在线玩家名补全。 */
    private static final SuggestionProvider<CommandSourceStack> ONLINE_PLAYERS = (ctx, builder) -> {
        List<String> names = new ArrayList<>();
        try {
            for (var p : ctx.getSource().getServer().getPlayerList().getPlayers()) {
                names.add(p.getGameProfile().getName());
            }
        } catch (Throwable ignored) {}
        return SharedSuggestionProvider.suggest(names, builder);
    };

    /** 把玩家名解析成 UUID 串（在线优先，其次档案缓存，最后玩家数据里的名字历史）。 */
    private static String resolvePlayerId(CommandContext<CommandSourceStack> ctx, String name) {
        try {
            var online = ctx.getSource().getServer().getPlayerList().getPlayerByName(name);
            if (online != null) return online.getUUID().toString();
        } catch (Throwable ignored) {}
        try {
            var cache = ctx.getSource().getServer().getProfileCache();
            if (cache != null) {
                var profile = cache.get(name);
                if (profile.isPresent()) return profile.get().getId().toString();
            }
        } catch (Throwable ignored) {}
        try {
            return net.minecraft.client.yiz.core.PlayerIdentityStore.resolveId(name);
        } catch (Throwable ignored) {}
        return null;
    }

    public static void register() {
        LiteralArgumentBuilder<CommandSourceStack> cmd = Commands.literal("yiz")
            .then(Commands.literal("config")
                // 配置整体需要作弊权限（管理员级）；危险项在 set 里再逐项校验（服主级）
                .requires(s -> s.hasPermission(YizConfig.PERM_ADMIN))
                .then(Commands.literal("list").executes(YizConfigCommand::list))
                .then(Commands.literal("save").executes(YizConfigCommand::save))
                .then(Commands.literal("reload").executes(YizConfigCommand::reload))
                .then(Commands.literal("player")
                    .then(Commands.literal("list").executes(YizConfigCommand::playerList))
                    .then(Commands.literal("exempt")
                        .then(Commands.argument("player", StringArgumentType.word())
                            .suggests(ONLINE_PLAYERS)
                            .executes(ctx -> playerSet(ctx, true))))
                    .then(Commands.literal("allow")
                        .then(Commands.argument("player", StringArgumentType.word())
                            .suggests(ONLINE_PLAYERS)
                            .executes(ctx -> playerSet(ctx, false)))))
                .then(Commands.literal("get")
                    .then(Commands.argument("key", StringArgumentType.word()).suggests(KEYS)
                        .executes(YizConfigCommand::get)))
                .then(Commands.literal("where")
                    .then(Commands.argument("key", StringArgumentType.word()).suggests(KEYS)
                        .executes(YizConfigCommand::where)))
                .then(Commands.literal("reset")
                    .then(Commands.argument("key", StringArgumentType.word()).suggests(KEYS)
                        .executes(YizConfigCommand::reset)))
                .then(Commands.literal("set")
                    .then(Commands.argument("key", StringArgumentType.word()).suggests(KEYS)
                        .then(Commands.argument("value", StringArgumentType.word())
                            .executes(YizConfigCommand::set)))));
        SimpleCommandRegistry.register(cmd);
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        var src = ctx.getSource();
        src.sendSuccess(() -> Component.literal("§6配置项（值 / 来源）："), false);
        String lastCat = null;
        for (YizConfig.Entry e : YizConfig.entries()) {
            boolean danger = "danger".equals(e.category());
            if (!e.category().equals(lastCat)) {
                lastCat = e.category();
                final String cat = lastCat;
                src.sendSuccess(() -> Component.literal(danger
                    ? "§8[§c" + cat + " §4⚠危险§8]"
                    : "§8[§7" + cat + "§8]"), false);
            }
            String line = "§f" + e.key() + " §7= §a" + YizConfig.display(e.key())
                + " §8(" + YizConfig.where(e.key()) + ")"
                + (e.perm() >= YizConfig.PERM_OWNER ? " §c[需服主]" : (e.perm() >= YizConfig.PERM_ADMIN ? " §e[需管理员]" : ""))
                + (e.when() == YizConfig.When.RESTART ? " §c[需重启]" : "");
            src.sendSuccess(() -> Component.literal(line), false);
        }
        src.sendSuccess(() -> Component.literal(
            "§7改一项：§f/yiz config set <键> <值>§7（只影响本次游戏）；§f/yiz config save§7 写盘永久"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int get(CommandContext<CommandSourceStack> ctx) {
        String key = StringArgumentType.getString(ctx, "key");
        YizConfig.Entry e = YizConfig.entry(key);
        if (e == null) return unknown(ctx, key);
        ctx.getSource().sendSuccess(() -> Component.literal(
            "§6" + key + " §7= §a" + YizConfig.display(key) + " §8(" + YizConfig.where(key) + ")\n§7" + e.note()), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int where(CommandContext<CommandSourceStack> ctx) {
        String key = StringArgumentType.getString(ctx, "key");
        if (YizConfig.entry(key) == null) return unknown(ctx, key);
        ctx.getSource().sendSuccess(() -> Component.literal(
            "§6" + key + " §7来自：§a" + YizConfig.where(key) + "§7，当前值 §a" + YizConfig.display(key)), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int set(CommandContext<CommandSourceStack> ctx) {
        String key = StringArgumentType.getString(ctx, "key");
        String value = StringArgumentType.getString(ctx, "value");
        YizConfig.Entry e = YizConfig.entry(key);
        if (e == null) return unknown(ctx, key);
        // 分级权限：危险项要服主级
        if (!hasPerm(ctx, e.perm())) {
            ctx.getSource().sendFailure(Component.literal(
                "§c权限不足：" + key + " 需要 " + permName(e.perm()) + "（等级 " + e.perm() + "）"));
            return 0;
        }
        if (!YizConfig.setSession(key, value)) {
            ctx.getSource().sendFailure(Component.literal(
                "§c值不合法：" + value + "（布尔项用 true/false 或 开/关）"));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal(
            "§a" + key + " §7= §a" + YizConfig.display(key) + " §8(本次游戏有效；/yiz config save 写盘永久)"
            + (e.perm() >= YizConfig.PERM_OWNER ? " §c⚠危险项" : "")), true);
        return Command.SINGLE_SUCCESS;
    }

    private static int playerList(CommandContext<CommandSourceStack> ctx) {
        var src = ctx.getSource();
        var lines = net.minecraft.client.yiz.core.PlayerIdentityStore.listLines();
        src.sendSuccess(() -> Component.literal("§6生物改血 · 个体开关（存在 players.json，跟身份走）："), false);
        for (String l : lines) {
            boolean exempt = false;
            try {
                String id = net.minecraft.client.yiz.core.PlayerIdentityStore.resolveId(
                    l.split(" ")[0]);
                exempt = id != null && YizConfig.isMobExempt(java.util.UUID.fromString(id));
            } catch (Throwable ignored) {}
            final boolean ex = exempt;
            src.sendSuccess(() -> Component.literal((ex ? "§c[豁免] " : "§7[正常] ") + "§f" + l), false);
        }
        src.sendSuccess(() -> Component.literal(
            "§7全局开关：§a" + YizConfig.display("danger.mob_health_on_player")
            + "§7（关着时个体开关无意义）"), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int playerSet(CommandContext<CommandSourceStack> ctx, boolean exempt) {
        String name = StringArgumentType.getString(ctx, "player");
        String id = resolvePlayerId(ctx, name);
        if (id == null) {
            ctx.getSource().sendFailure(Component.literal(
                "§c找不到玩家 " + name + "（需在线，或该名字在服务器档案/玩家数据里有记录）"));
            return 0;
        }
        boolean changed = YizConfig.setMobExempt(java.util.UUID.fromString(id), exempt);
        ctx.getSource().sendSuccess(() -> Component.literal(changed
            ? "§a" + name + " 已" + (exempt ? "§c不受生物改血影响" : "§a恢复受生物改血影响") + "§8（已落盘）"
            : "§7" + name + " 状态未变化（当前" + (exempt ? "已豁免" : "正常") + "）"), true);
        return Command.SINGLE_SUCCESS;
    }

    private static String nameOf(CommandContext<CommandSourceStack> ctx, String uuid) {
        try {
            var p = ctx.getSource().getServer().getPlayerList().getPlayer(java.util.UUID.fromString(uuid));
            if (p != null) return p.getGameProfile().getName() + " §8(" + uuid + ")";
        } catch (Throwable ignored) {}
        return uuid;
    }

    private static boolean hasPerm(CommandContext<CommandSourceStack> ctx, int level) {
        try {
            return ctx.getSource().hasPermission(level);
        } catch (Throwable t) {
            return false;
        }
    }

    private static String permName(int level) {
        if (level >= YizConfig.PERM_OWNER) return "服主";
        if (level >= YizConfig.PERM_ADMIN) return "管理员";
        return "任何人";
    }

    private static int reset(CommandContext<CommandSourceStack> ctx) {
        String key = StringArgumentType.getString(ctx, "key");
        if (YizConfig.entry(key) == null) return unknown(ctx, key);
        boolean had = YizConfig.clearSession(key);
        ctx.getSource().sendSuccess(() -> Component.literal(
            had ? "§a已撤销 " + key + " 的临时改动，当前值 §a" + YizConfig.display(key)
                : "§7" + key + " 没有临时改动，当前值 §a" + YizConfig.display(key)), true);
        return Command.SINGLE_SUCCESS;
    }

    private static int save(CommandContext<CommandSourceStack> ctx) {
        int n = YizConfig.saveSession();
        if (n < 0) {
            ctx.getSource().sendFailure(Component.literal("§c写盘失败（配置未加载？稍后再试）"));
            return 0;
        }
        int written = n;
        ctx.getSource().sendSuccess(() -> Component.literal(written == 0
            ? "§7没有需要写盘的临时改动"
            : "§a已写盘 " + written + " 项到 §fconfig/" + YizConfig.FILE_NAME + "§a（永久生效）"), true);
        return Command.SINGLE_SUCCESS;
    }

    private static int reload(CommandContext<CommandSourceStack> ctx) {
        int n = YizConfig.reload();
        if (n < 0) {
            ctx.getSource().sendFailure(Component.literal("§c读取失败：文件语法可能有误，看日志"));
            return 0;
        }
        int read = n;
        ctx.getSource().sendSuccess(() -> Component.literal(
            "§a已重读 §fconfig/" + YizConfig.FILE_NAME + "§a，生效 " + read + " 项"), true);
        return Command.SINGLE_SUCCESS;
    }

    private static int unknown(CommandContext<CommandSourceStack> ctx, String key) {
        ctx.getSource().sendFailure(Component.literal("§c未知配置项: " + key + "（用 /yiz config list 查看）"));
        return 0;
    }
}
