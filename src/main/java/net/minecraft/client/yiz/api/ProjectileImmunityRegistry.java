package net.minecraft.client.yiz.api;

import net.minecraft.world.entity.LivingEntity;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 投射物免疫注册表（凋灵风格远程免疫）
 * 下游注册条件，前置在 hurt() 中拦截投射物伤害直接返回 false。
 */
// 大白话: 投射免方法
public final class ProjectileImmunityRegistry {

    private static final List<Condition> CONDITIONS = new CopyOnWriteArrayList<>();

    private ProjectileImmunityRegistry() {}

    @FunctionalInterface
    public interface Condition {
        boolean isImmune(LivingEntity entity);
    }

    public static void register(Condition condition) {
        CONDITIONS.add(condition);
    }

    public static boolean isImmune(LivingEntity entity) {
        for (Condition c : CONDITIONS) {
            if (c.isImmune(entity)) return true;
        }
        return false;
    }
}
