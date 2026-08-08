package net.minecraft.client.yiz.api;

import net.minecraft.world.entity.LivingEntity;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 击退免疫注册表
 */
// 大白话: 击退免疫方法
public final class KnockbackImmunityRegistry {

    private static final List<Condition> CONDITIONS = new CopyOnWriteArrayList<>();

    private KnockbackImmunityRegistry() {}

    @FunctionalInterface
    public interface Condition {
        boolean isImmune(LivingEntity entity);
    }

    public static void register(Condition condition) {
        CONDITIONS.add(condition);
    }

    public static boolean isImmune(LivingEntity entity) {
        for (Condition c : CONDITIONS) if (c.isImmune(entity)) return true;
        return false;
    }
}
