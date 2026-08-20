package net.minecraft.client.yiz.tool.health;

import net.minecraft.world.entity.LivingEntity;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 可达对象图数值扫描器（P0.5 全量直改 / 对齐外部模组「可达对象容器」思路）。
 *
 * <p>从实体出发沿实例字段 BFS（深度 ≤2、对象数封顶），收集沿途对象与实体自身的
 * 数值字段（float/double/int/long，非 static/final）。血量常藏在「实体 → 容器对象 →
 * 容器对象」的多跳结构里，单层字段扫描会漏。</p>
 *
 * <p>黑名单包（java./net.minecraft./…）不参与<b>递归</b>，避免扫进 Minecraft
 * 内部巨量对象；被黑名单对象自身的数值字段仍可收集（由调用方按「值与血量相等」过滤，
 * 不会误写无关数据）。结果按类缓存。</p>
 */
public final class ReachableGraphScanner {

    private static final int MAX_DEPTH = 2;
    private static final int MAX_OBJECTS = 48;
    private static final int MAX_REFS = 128;
    private static final Set<String> PACKAGE_BLACKLIST = Set.of(
        "java.", "net.minecraft.", "com.google.", "it.unimi.", "org.apache.", "io.netty.",
        "com.mojang.", "org.spongepowered."
    );

    private static final Map<Class<?>, List<Field>> FIELD_CACHE = new ConcurrentHashMap<>();

    private ReachableGraphScanner() {}

    /** 扫描实体可达对象图，返回全部数值字段引用（含实体自身层级字段，不含 LivingEntity 基类字段）。 */
    public static List<ValueRef> scan(LivingEntity entity) {
        List<ValueRef> out = new ArrayList<>();
        if (entity == null) return out;

        // 实体自身：只扫 LivingEntity 之下的字段（避免实体坐标/旋转等无关数值进候选）
        for (Class<?> c = entity.getClass(); c != null && c != LivingEntity.class; c = c.getSuperclass()) {
            collectNumericFields(entity, java.util.Arrays.asList(c.getDeclaredFields()), out);
        }

        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        visited.add(entity);
        Deque<Object> queue = new ArrayDeque<>();
        queue.add(entity);
        int depth = 0;
        while (!queue.isEmpty() && depth < MAX_DEPTH && out.size() < MAX_REFS) {
            int size = queue.size();
            for (int i = 0; i < size && out.size() < MAX_REFS; i++) {
                Object obj = queue.poll();
                if (obj == null) continue;
                // 可达对象的数值字段
                collectNumericFields(obj, allFields(obj.getClass()), out);
                // 递归入口：非黑名单包、非容器/原始类型的实例字段
                for (Field f : allFields(obj.getClass())) {
                    int mod = f.getModifiers();
                    if (Modifier.isStatic(mod) || Modifier.isFinal(mod)) continue;
                    Class<?> t = f.getType();
                    if (t.isPrimitive() || t == String.class || t.isEnum()) continue;
                    if (t.isArray() || Collection.class.isAssignableFrom(t) || Map.class.isAssignableFrom(t)) continue;
                    if (blacklisted(t.getName())) continue;
                    try {
                        f.setAccessible(true);
                        Object v = f.get(obj);
                        if (v != null && v != entity && visited.add(v)) {
                            queue.add(v);
                        }
                    } catch (Throwable ignored) {}
                }
            }
            depth++;
        }
        return out;
    }

    private static void collectNumericFields(Object obj, List<Field> fields, List<ValueRef> out) {
        for (Field f : fields) {
            int mod = f.getModifiers();
            if (Modifier.isStatic(mod) || Modifier.isFinal(mod) || f.isSynthetic()) continue;
            Class<?> t = f.getType();
            if (t != float.class && t != double.class && t != int.class && t != long.class) continue;
            try {
                f.setAccessible(true);
                out.add(new ValueRef.FieldValueRef(obj, f));
            } catch (Throwable ignored) {}
        }
    }

    private static List<Field> allFields(Class<?> clazz) {
        return FIELD_CACHE.computeIfAbsent(clazz, c -> {
            List<Field> list = new ArrayList<>();
            for (Class<?> cur = c; cur != null && cur != Object.class; cur = cur.getSuperclass()) {
                Collections.addAll(list, cur.getDeclaredFields());
            }
            return list;
        });
    }

    private static boolean blacklisted(String className) {
        for (String prefix : PACKAGE_BLACKLIST) {
            if (className.startsWith(prefix)) return true;
        }
        return false;
    }
}
