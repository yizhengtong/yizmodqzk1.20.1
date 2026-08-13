package net.minecraft.client.yiz.tool;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.client.yiz.core.asm.AgentBridge;
import net.minecraft.client.yiz.tool.key.KeyDumpBridge;
import net.minecraft.client.yiz.tool.key.KeyHunter;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * /yiz key — 通用「内部 Key 判定」攻取工具（KeyHunter 命令行入口）。
 *
 * <ul>
 *   <li>{@code /yiz key scan <包前缀...>} — 四步攻取：枚举→按类型定位→Unsafe 夺取密钥
 *       →闸门中和（StackWalker 置换/白名单扩展）+ 握手伪造就绪；</li>
 *   <li>{@code /yiz key watch <包前缀...>} — agent 字节码层：对目标包 retransform，
 *       改写 StackWalker 调用点 + 在密钥比较点转储操作数（对付密钥不落字段的硬化目标）；</li>
 *   <li>{@code /yiz key unwatch} — 还原 retransform 并清空捕获；</li>
 *   <li>{@code /yiz key report} — 展示 watch 状态与已捕获密钥。</li>
 * </ul>
 * 只按包前缀与类型特征工作，不引用任何目标模组名字。
 */
public final class YizKeyCommand {

    private YizKeyCommand() {}

    public static void register() {
        LiteralArgumentBuilder<CommandSourceStack> cmd = Commands.literal("yiz")
            .then(Commands.literal("key")
                .executes(YizKeyCommand::usage)
                .then(Commands.literal("scan")
                    .then(Commands.argument("prefixes", StringArgumentType.greedyString())
                        .executes(ctx -> scan(ctx, splitPrefixes(ctx, "prefixes")))))
                .then(Commands.literal("watch")
                    .then(Commands.argument("prefixes", StringArgumentType.greedyString())
                        .executes(ctx -> watch(ctx, splitPrefixes(ctx, "prefixes")))))
                .then(Commands.literal("unwatch")
                    .executes(YizKeyCommand::unwatch))
                .then(Commands.literal("report")
                    .executes(YizKeyCommand::report))
            );
        SimpleCommandRegistry.register(cmd);
    }

    private static int usage(CommandContext<CommandSourceStack> ctx) {
        send(ctx, "§e/yiz key 用法:\n"
            + "§7  /yiz key scan <包前缀...>   §f四步攻取（枚举/定位/夺取/通行）\n"
            + "§7  /yiz key watch <包前缀...>  §f字节码层 watch（比较点密钥转储）\n"
            + "§7  /yiz key unwatch           §f关闭 watch 并还原字节码\n"
            + "§7  /yiz key report            §fwatch 状态与捕获密钥");
        return 1;
    }

    private static int scan(CommandContext<CommandSourceStack> ctx, List<String> prefixes) {
        if (prefixes.isEmpty()) {
            send(ctx, "§c至少给一个包前缀（不含空格，多个用空格分隔）");
            return 0;
        }
        send(ctx, "§7[KeyHunter] 开始攻取: " + String.join(" ", prefixes) + " …");
        try {
            KeyHunter.KeyHuntReport report = KeyHunter.hunt(prefixes.toArray(new String[0]));
            send(ctx, report.toString());
        } catch (Throwable t) {
            send(ctx, "§c[KeyHunter] 攻取异常: " + t.getClass().getSimpleName() + " - " + t.getMessage());
        }
        return 1;
    }

    private static int watch(CommandContext<CommandSourceStack> ctx, List<String> prefixes) {
        if (prefixes.isEmpty()) {
            send(ctx, "§c至少给一个包前缀");
            return 0;
        }
        int count = AgentBridge.enableKeyWatch(prefixes);
        if (count == -1) {
            send(ctx, "§cagent 不可用（Instrumentation 未取得）——先 /yiz agent 诊断");
        } else if (count == -2) {
            send(ctx, "§cretransform 失败: " + AgentBridge.getLastError());
        } else {
            send(ctx, "§a[KeyWatch] 开启: " + String.join(" ", AgentBridge.getKeyWatchPrefixes())
                + " §7(retransform " + count + " 个已加载类；之后新加载的同类自动注入)");
        }
        return 1;
    }

    private static int unwatch(CommandContext<CommandSourceStack> ctx) {
        int count = AgentBridge.disableKeyWatch();
        if (count == -2) {
            send(ctx, "§cunwatch retransform 失败: " + AgentBridge.getLastError());
        } else {
            send(ctx, "§a[KeyWatch] 已关闭（" + count + " 个类还原为原始字节码），捕获已清空");
        }
        return 1;
    }

    private static int report(CommandContext<CommandSourceStack> ctx) {
        List<String> prefixes = AgentBridge.getKeyWatchPrefixes();
        Map<String, byte[]> bytes = KeyDumpBridge.capturedBytes();
        Map<String, String> texts = KeyDumpBridge.capturedText();
        StringBuilder sb = new StringBuilder("§e=== /yiz key report ===\n");
        sb.append("§7[watch] ").append(prefixes.isEmpty()
            ? "§c未开启" : "§a" + String.join(" ", prefixes)).append("\n");
        sb.append("§7[捕获] byte[] 密钥: §f").append(bytes.size())
            .append("§7 | String 密钥: §f").append(texts.size()).append("\n");
        for (Map.Entry<String, byte[]> e : bytes.entrySet()) {
            sb.append("§d  ").append(e.getKey()).append(" → byte[")
                .append(e.getValue().length).append("]\n");
        }
        for (Map.Entry<String, String> e : texts.entrySet()) {
            sb.append("§d  ").append(e.getKey()).append(" → \"").append(e.getValue()).append("\"\n");
        }
        if (bytes.isEmpty() && texts.isEmpty()) {
            sb.append("§7  无捕获——目标判定尚未跑比较（触发一次目标操作后 re-report）\n");
        }
        send(ctx, sb.toString());
        return 1;
    }

    private static List<String> splitPrefixes(CommandContext<CommandSourceStack> ctx, String arg) {
        String greedy = StringArgumentType.getString(ctx, arg);
        return Arrays.stream(greedy.trim().split("\\s+"))
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private static void send(CommandContext<CommandSourceStack> ctx, String msg) {
        CommandSourceStack source = ctx.getSource();
        if (source.getEntity() instanceof ServerPlayer player) {
            player.sendSystemMessage(Component.literal(msg));
        } else {
            source.sendSuccess(() -> Component.literal(msg), false);
        }
    }
}
