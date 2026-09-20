package net.minecraft.client.yiz.network;

import net.minecraft.client.yiz.tizMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

/**
 * 前置库 SimpleChannel 网络（1.20.1 移植版，参照下游 yizxianmod 模式）。
 */
public final class NetworkHandler {

    private static final String PROTOCOL_VERSION = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
        new ResourceLocation(tizMod.MODID, "main"),
        () -> PROTOCOL_VERSION,
        PROTOCOL_VERSION::equals,
        PROTOCOL_VERSION::equals
    );

    private static int packetId = 0;

    private NetworkHandler() {}

    public static void register() {
        CHANNEL.registerMessage(packetId++,
            S2CShockFxPayload.class,
            S2CShockFxPayload::encode,
            S2CShockFxPayload::decode,
            S2CShockFxPayload::handle
        );
        CHANNEL.registerMessage(packetId++,
            C2SMultiJumpPayload.class,
            C2SMultiJumpPayload::encode,
            C2SMultiJumpPayload::decode,
            C2SMultiJumpPayload::handle
        );
        CHANNEL.registerMessage(packetId++,
            S2CLaunchFxPayload.class,
            S2CLaunchFxPayload::encode,
            S2CLaunchFxPayload::decode,
            S2CLaunchFxPayload::handle
        );
        CHANNEL.registerMessage(packetId++,
            net.minecraft.client.yiz.editor.C2SAttributeEditorPayload.class,
            net.minecraft.client.yiz.editor.C2SAttributeEditorPayload::encode,
            net.minecraft.client.yiz.editor.C2SAttributeEditorPayload::decode,
            net.minecraft.client.yiz.editor.C2SAttributeEditorPayload::handle
        );
        // ── 万能物品配置（itemcfg） ──
        CHANNEL.registerMessage(packetId++,
            net.minecraft.client.yiz.itemcfg.network.C2SItemConfigQueryPayload.class,
            net.minecraft.client.yiz.itemcfg.network.C2SItemConfigQueryPayload::encode,
            net.minecraft.client.yiz.itemcfg.network.C2SItemConfigQueryPayload::decode,
            net.minecraft.client.yiz.itemcfg.network.C2SItemConfigQueryPayload::handle
        );
        CHANNEL.registerMessage(packetId++,
            net.minecraft.client.yiz.itemcfg.network.S2CItemConfigStatePayload.class,
            net.minecraft.client.yiz.itemcfg.network.S2CItemConfigStatePayload::encode,
            net.minecraft.client.yiz.itemcfg.network.S2CItemConfigStatePayload::decode,
            net.minecraft.client.yiz.itemcfg.network.S2CItemConfigStatePayload::handle
        );
        CHANNEL.registerMessage(packetId++,
            net.minecraft.client.yiz.itemcfg.network.C2SItemConfigTogglePayload.class,
            net.minecraft.client.yiz.itemcfg.network.C2SItemConfigTogglePayload::encode,
            net.minecraft.client.yiz.itemcfg.network.C2SItemConfigTogglePayload::decode,
            net.minecraft.client.yiz.itemcfg.network.C2SItemConfigTogglePayload::handle
        );
    }
}
