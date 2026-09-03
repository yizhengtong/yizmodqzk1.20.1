package net.minecraft.client.yiz.itemcfg;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * /yiz itemcfg 管理指令（SimpleCommandRegistry 注册）。
 * - reload：重载适配层 + 重新扫描
 * - dump：提示查看 items.json
 * - setglobal <item> <feature> on|off：管理员设全局默认（需权限 2）
 */
public final class YizItemConfigCommand {

    private YizItemConfigCommand() {}

    public static void register() {
        net.minecraft.client.yiz.tool.SimpleCommandRegistry.register(
            Commands.literal("yiz").then(Commands.literal("itemcfg")
                .then(Commands.literal("reload").executes(ctx -> {
                    AdapterRegistry.loadAll();
                    ItemFeatureDiscoverer.scanAll();
                    ctx.getSource().sendSuccess(() -> Component.literal("[ItemConfig] 适配层+扫描已重载"), false);
                    return 1;
                }))
                .then(Commands.literal("dump").executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Component.literal(
                            "[ItemConfig] 扫描结果见 config/yizmodqzk/items.json"), false);
                    return 1;
                }))
                .then(Commands.literal("setglobal")
                    .requires(s -> s.hasPermission(2))
                    .then(Commands.argument("item", StringArgumentType.word())
                        .then(Commands.argument("feature", StringArgumentType.word())
                            .then(Commands.literal("on").executes(ctx -> setGlobal(
                                ctx.getSource(),
                                StringArgumentType.getString(ctx, "item"),
                                StringArgumentType.getString(ctx, "feature"), false)))
                            .then(Commands.literal("off").executes(ctx -> setGlobal(
                                ctx.getSource(),
                                StringArgumentType.getString(ctx, "item"),
                                StringArgumentType.getString(ctx, "feature"), true)))
                        )))
                .then(Commands.literal("abolish")
                    .requires(s -> s.hasPermission(2))
                    .then(Commands.argument("item", StringArgumentType.word())
                        .then(Commands.argument("feature", StringArgumentType.word())
                            .then(Commands.literal("on").executes(ctx -> abolish(
                                ctx.getSource(),
                                StringArgumentType.getString(ctx, "item"),
                                StringArgumentType.getString(ctx, "feature"), true)))
                            .then(Commands.literal("off").executes(ctx -> abolish(
                                ctx.getSource(),
                                StringArgumentType.getString(ctx, "item"),
                                StringArgumentType.getString(ctx, "feature"), false)))
                        )))
            ));
    }

    private static int setGlobal(net.minecraft.commands.CommandSourceStack src, String itemStr, String feature, boolean disabled) {
        ResourceLocation id = ResourceLocation.tryParse(itemStr);
        if (id == null) {
            src.sendFailure(Component.literal("[ItemConfig] 非法物品 id: " + itemStr));
            return 0;
        }
        boolean known = FeatureType.fromId(feature) != null || AdapterRegistry.isKnown(id, feature);
        if (!known) {
            src.sendFailure(Component.literal("[ItemConfig] 未知功能: " + feature));
            return 0;
        }
        ConfigRegistry.setGlobalDisabled(id, feature, disabled);
        src.sendSuccess(() -> Component.literal("[ItemConfig] 全局 " + id + " " + feature + " -> " + (disabled ? "关闭" : "开启")), false);
        return 1;
    }

    /** 管理员：全局废除某物品某结构功能（VTable 覆写，全服生效）。 */
    private static int abolish(net.minecraft.commands.CommandSourceStack src, String itemStr, String feature, boolean doAbolish) {
        ResourceLocation id = ResourceLocation.tryParse(itemStr);
        if (id == null) {
            src.sendFailure(Component.literal("[ItemConfig] 非法物品 id: " + itemStr));
            return 0;
        }
        if (FeatureType.fromId(feature) == null) {
            src.sendFailure(Component.literal("[ItemConfig] 未知结构功能: " + feature));
            return 0;
        }
        if (doAbolish) {
            ItemConfigAbolition.abolish(id, feature);
            src.sendSuccess(() -> Component.literal("[ItemConfig] 全局废除 " + id + " " + feature), false);
        } else {
            ItemConfigAbolition.restore(id, feature);
            src.sendSuccess(() -> Component.literal("[ItemConfig] 取消废除 " + id + " " + feature + "（重启生效）"), false);
        }
        return 1;
    }
}
