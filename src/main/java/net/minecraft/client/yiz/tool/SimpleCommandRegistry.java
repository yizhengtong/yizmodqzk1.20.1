package net.minecraft.client.yiz.tool;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.common.MinecraftForge;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 简易指令注册器（1.20.1 移植版）。
 *
 * <p>下游模组调用 {@link #register} 提交指令，无需自行订阅 {@link RegisterCommandsEvent}。
 * 指令在服务端启动时自动注册到 {@link CommandDispatcher}。</p>
 */
public final class SimpleCommandRegistry {

    private static final List<LiteralArgumentBuilder<CommandSourceStack>> pending = new CopyOnWriteArrayList<>();
    private static boolean registered = false;

    private SimpleCommandRegistry() {}

    /** 注册 Forge 事件监听（由 tizMod 调用一次）。 */
    public static void init() {
        if (registered) return;
        registered = true;
        MinecraftForge.EVENT_BUS.register(SimpleCommandRegistry.class);
    }

    /** 提交一个指令。 */
    public static void register(LiteralArgumentBuilder<CommandSourceStack> builder) {
        if (builder == null) {
            throw new IllegalArgumentException("builder must not be null");
        }
        pending.add(builder);
    }

    /** 快捷注册：无参数的字面指令。 */
    public static void register(String name, Command<CommandSourceStack> action) {
        if (name == null) {
            throw new IllegalArgumentException("name must not be null");
        }
        if (action == null) {
            throw new IllegalArgumentException("action must not be null");
        }
        register(Commands.literal(name).executes(action));
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        for (var builder : pending) {
            dispatcher.register(builder);
        }
    }
}
