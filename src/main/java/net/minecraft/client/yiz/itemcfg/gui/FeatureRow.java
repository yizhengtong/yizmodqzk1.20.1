package net.minecraft.client.yiz.itemcfg.gui;

/**
 * GUI 功能行模型。disabled=当前生效关闭状态；declaredBy: 0=结构功能，1=语义功能(adapter)。
 */
public record FeatureRow(String feature, String zh, boolean disabled, byte declaredBy) {}
