package net.minecraft.client.yiz.tool.defense;

import net.minecraft.world.entity.LivingEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * P2a：运行时“字段上限”破除。
 *
 * <p>依据 {@link DefenseScanner} 的画像，在命中前把目标类里疑似上限/冷却/无敌帧的
 * 数值字段临时改写为 MAX 或 0，命中结束立即还原（只在本次 hurt 生效）。</p>
 *
 * <p>只处理名字明确属于「上限/冷却/时间窗/无敌帧」的字段，且跳过 health/hp/life/heal，
 * 避免误改生命值或无关状态。</p>
 */
public final class DefenseFieldBreaker {

    private static final Logger LOGGER = LoggerFactory.getLogger("yizmodqzk-pohuan");

    /** 字段名 → 写入值策略。 */
    private static final java.util.regex.Pattern CAP = java.util.regex.Pattern.compile(
        "(?i).*(cap|limit|maxdamage|maxhurt|maxhit|damagemax|hurtmax|hitmax).*");
    private static final java.util.regex.Pattern ZERO = java.util.regex.Pattern.compile(
        "(?i).*(cooldown|invul|iframe|nodamageticks|hurtduration|hurttime|lastdamagetick|lasthurttick|damagetick|hittick).*");
    private static final java.util.regex.Pattern SKIP = java.util.regex.Pattern.compile(
        "(?i).*(health|\\bhp\\b|life|heal).*");

    private DefenseFieldBreaker() {}

    private static final Map<Class<?>, Map<String, Field>> FIELD_CACHE = new ConcurrentHashMap<>();
    private static final Set<String> LOGGED = ConcurrentHashMap.newKeySet();
    private static final ThreadLocal<List<Object[]>> SNAPSHOT = ThreadLocal.withInitial(ArrayList::new);

    /** 命中前：按画像临时放宽字段上限；结束后由 {@link #restore()} 还原。 */
    public static void neutralize(LivingEntity target) {
        if (target == null) return;
        DefenseProfile p = DefenseProfile.cached(target.getClass().getName());
        if (p == null) return;
        List<Object[]> snap = SNAPSHOT.get();
        restore();   // 防上次异常未还原

        if (p.fields != null) {
            for (DefenseProfile.FieldCandidate c : p.fields) {
                if (c.name == null) continue;
                String ln = c.name.toLowerCase(Locale.ROOT);
                if (SKIP.matcher(ln).matches()) continue;
                boolean isCap = CAP.matcher(ln).matches();
                boolean isZero = ZERO.matcher(ln).matches();
                if (!isCap && !isZero) continue;
                apply(target, c, isCap ? maxFor(typeOf(c.desc)) : zeroFor(typeOf(c.desc)), p.className, snap);
            }
        }

        // 结构语义门（名字不可靠也能命中）：boolean 门 → false；时间/计时器门 → 0
        if (net.minecraft.client.yiz.core.YizConfig.bool("pohuan.structural_gates") && p.gates != null) {
            for (DefenseProfile.FieldCandidate g : p.gates) {
                if (g.name == null || g.kind == null) continue;
                // 不改原版核心类（Entity/LivingEntity 等）的内部状态，避免破坏基础逻辑
                if (g.owner != null && g.owner.startsWith("net/minecraft/world/entity/")) continue;
                Object now = "bool".equals(g.kind) ? Boolean.FALSE : zeroFor(typeOf(g.desc));
                if (now != null) apply(target, g, now, p.className, snap);
            }
        }
    }

    private static void apply(LivingEntity target, DefenseProfile.FieldCandidate c, Object now,
                              String className, List<Object[]> snap) {
        try {
            Field f = resolve(target.getClass(), c.name, c.desc);
            if (f == null || Modifier.isStatic(f.getModifiers())) return;
            Object old = f.get(target);
            if (now == null || now.equals(old)) return;
            f.set(target, now);
            snap.add(new Object[]{f, old, target});
            if (LOGGED.add(className + "#" + c.name + "#" + c.kind)) {
                LOGGER.info("[破幻寻真] 字段破除 {}#{}: {} → {}{}", className, c.name, old, now,
                    c.kind == null ? "" : " (" + c.kind + ")");
            }
        } catch (Throwable ignored) {}
    }

    /** 还原本次命中前记录的原值。 */
    public static void restore() {
        List<Object[]> snap = SNAPSHOT.get();
        for (int i = snap.size() - 1; i >= 0; i--) {
            Object[] e = snap.get(i);
            try {
                Field f = (Field) e[0];
                LivingEntity t = (LivingEntity) e[2];
                f.set(t, e[1]);
            } catch (Throwable ignored) {}
        }
        snap.clear();
    }

    private static Field resolve(Class<?> cls, String name, String desc) {
        Map<String, Field> m = FIELD_CACHE.computeIfAbsent(cls, k -> new ConcurrentHashMap<>());
        Field cached = m.get(name + desc);
        if (cached != null) return cached;
        Class<?> c = cls;
        while (c != null && c != Object.class) {
            for (Field f : c.getDeclaredFields()) {
                if (!f.getName().equals(name)) continue;
                if (!descMatches(f.getType(), desc)) continue;
                try { f.setAccessible(true); } catch (Throwable ignored) {}
                m.put(name + desc, f);
                return f;
            }
            c = c.getSuperclass();
        }
        return null;
    }

    private static boolean descMatches(Class<?> t, String desc) {
        return switch (desc) {
            case "I" -> t == int.class;
            case "J" -> t == long.class;
            case "F" -> t == float.class;
            case "D" -> t == double.class;
            case "Z" -> t == boolean.class;
            default -> false;
        };
    }

    private static Class<?> typeOf(String desc) {
        return switch (desc) {
            case "I" -> int.class;
            case "J" -> long.class;
            case "F" -> float.class;
            case "D" -> double.class;
            case "Z" -> boolean.class;
            default -> null;
        };
    }

    private static Object maxFor(Class<?> t) {
        if (t == int.class) return Integer.MAX_VALUE;
        if (t == long.class) return Long.MAX_VALUE;
        if (t == float.class) return Float.MAX_VALUE;
        if (t == double.class) return Double.MAX_VALUE;
        return null;
    }

    private static Object zeroFor(Class<?> t) {
        if (t == int.class) return 0;
        if (t == long.class) return 0L;
        if (t == float.class) return 0.0F;
        if (t == double.class) return 0.0D;
        return null;
    }
}
