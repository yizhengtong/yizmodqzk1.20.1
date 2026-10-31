package net.minecraft.client.yiz.tool.defense;

import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.fml.loading.FMLPaths;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.CRC32;

/**
 * 防御画像扫描器（P1：只读）。
 *
 * <p>对 {@link LivingEntity} 子类的 class 字节做只读 ASM 遍历，收集：</p>
 * <ul>
 *   <li>被覆写的生命/伤害方法；</li>
 *   <li>{@code hurt}/{@code actuallyHurt}/{@code setHealth} 内 {@code Math.min/max} 附近的数值常量
 *       （疑似单次/时间窗上限）；</li>
 *   <li>目标方法内读写的数值字段（疑似上限/冷却）；</li>
 *   <li><b>结构语义门</b>（不依赖名字）：
 *     <ul>
 *       <li>{@code bool}：hurt 里读取的 boolean 字段（无敌/阶段/状态门）；</li>
 *       <li>{@code time}：hurt 里与 {@code tickCount}/{@code getGameTime}/{@code currentTimeMillis}
 *           同一段代码读取的数值字段（tick/绝对时间无敌）；</li>
 *       <li>{@code timer}：在 tick/aiStep/baseTick 里被写、又在 hurt 里被读的数值字段
 *           （每 tick 递减的计时器，完全不看 tick 的实体不会有这类字段，但 time 门/布尔门能兜住）。</li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * <p>只读，不改字节码；结果缓存到 {@code config/yizmodqzk/defense_profiles.json}。</p>
 */
public final class DefenseScanner {

    private static final Set<String> TARGET_METHODS = Set.of("hurt", "actuallyHurt", "setHealth");
    private static final Set<String> TICK_METHODS = Set.of("tick", "aiStep", "baseTick", "customServerAiStep");
    private static final Set<String> TRACKED_METHODS = Set.of(
        "hurt", "actuallyHurt", "setHealth", "getHealth", "isInvulnerableTo",
        "die", "isDeadOrDying", "isAlive");

    private static final java.util.regex.Pattern FIELD_HINT = java.util.regex.Pattern.compile(
        "(?i).*(invul|iframe|hurt|damage|cap|limit|max|cooldown|tick).*");

    private DefenseScanner() {}

    private static final Set<String> SCANNED = ConcurrentHashMap.newKeySet();
    private static volatile long LAST_SAVE = 0L;
    private static final Path FILE = FMLPaths.CONFIGDIR.get().resolve("yizmodqzk/defense_profiles.json");
    private static volatile boolean loaded = false;

    private static void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        try {
            DefenseProfile.load(FILE);
        } catch (Throwable ignored) {}
    }

    /** 取类画像；首次调用会扫描“本类 + 全部父类”并合并缓存。可能返回 null。 */
    public static DefenseProfile ensureScanned(Class<?> cls) {
        ensureLoaded();
        if (cls == null) return null;
        String name = cls.getName();
        if (name.startsWith("net.minecraft.client.yiz")) return null;
        if (name.contains("$$")) return null;
        if (!LivingEntity.class.isAssignableFrom(cls)) return null;
        if (!SCANNED.add(name)) {
            return DefenseProfile.cached(name);
        }
        try {
            DefenseProfile merged = new DefenseProfile();
            merged.className = name;
            merged.scannedAt = System.currentTimeMillis();
            for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
                DefenseProfile p = scanClass(c);
                if (p == null) continue;
                merged.classHash ^= p.classHash;
                merged.overrides.addAll(p.overrides);
                mergeCaps(merged, p);
                mergeFields(merged, p);
            }
            DefenseProfile.put(merged);
            long now = System.currentTimeMillis();
            if (now - LAST_SAVE > 5000L) {
                LAST_SAVE = now;
                DefenseProfile.save(FILE);
            }
            return merged;
        } catch (Throwable t) {
            return DefenseProfile.cached(name);
        }
    }

    private static void mergeCaps(DefenseProfile merged, DefenseProfile p) {
        for (DefenseProfile.CapCandidate cap : p.caps) {
            for (DefenseProfile.CapCandidate x : merged.caps) {
                if (x.method.equals(cap.method) && x.value == cap.value && x.type.equals(cap.type)) return;
            }
            merged.caps.add(cap);
        }
    }

    private static void mergeFields(DefenseProfile merged, DefenseProfile p) {
        for (DefenseProfile.FieldCandidate f : p.fields) {
            boolean dup = false;
            for (DefenseProfile.FieldCandidate x : merged.fields) {
                if (x.owner.equals(f.owner) && x.name.equals(f.name) && (x.kind == null ? f.kind == null : x.kind.equals(f.kind))) {
                    dup = true;
                    break;
                }
            }
            if (!dup) merged.fields.add(f);
        }
        for (DefenseProfile.FieldCandidate g : p.gates) {
            boolean dup = false;
            for (DefenseProfile.FieldCandidate x : merged.gates) {
                if (x.owner.equals(g.owner) && x.name.equals(g.name) && x.kind.equals(g.kind)) {
                    dup = true;
                    break;
                }
            }
            if (!dup) merged.gates.add(g);
        }
    }

    private static DefenseProfile scanClass(Class<?> cls) {
        String n = cls.getName();
        DefenseProfile cached = DefenseProfile.cached(n);
        if (cached != null) return cached;
        try {
            byte[] bytes = readBytes(cls);
            if (bytes == null) return null;
            DefenseProfile p = scan(n, bytes);
            DefenseProfile.put(p);
            return p;
        } catch (Throwable t) {
            return null;
        }
    }

    private static byte[] readBytes(Class<?> cls) {
        try {
            ClassLoader cl = cls.getClassLoader();
            String path = cls.getName().replace('.', '/') + ".class";
            InputStream in = cl != null ? cl.getResourceAsStream(path) : ClassLoader.getSystemResourceAsStream(path);
            if (in == null) return null;
            try (InputStream is = in) {
                return is.readAllBytes();
            }
        } catch (Throwable t) {
            return null;
        }
    }

    private static DefenseProfile scan(String className, byte[] bytes) {
        DefenseProfile p = new DefenseProfile();
        p.className = className;
        CRC32 crc = new CRC32();
        crc.update(bytes);
        p.classHash = crc.getValue();
        p.scannedAt = System.currentTimeMillis();

        // 跨方法合并用的临时集合（类级）
        java.util.Map<String, String> owners = new java.util.HashMap<>();
        java.util.Map<String, String> descs = new java.util.HashMap<>();
        Set<String> tickWritten = ConcurrentHashMap.newKeySet();
        Set<String> hitWritten = ConcurrentHashMap.newKeySet();
        Set<String> hitWrittenBool = ConcurrentHashMap.newKeySet();
        Set<String> targetRead = ConcurrentHashMap.newKeySet();
        Set<String> timeRead = ConcurrentHashMap.newKeySet();
        Set<String> boolRead = ConcurrentHashMap.newKeySet();

        ClassReader cr = new ClassReader(bytes);
        cr.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] ex) {
                if (TRACKED_METHODS.contains(name)) p.overrides.add(name);
                boolean target = TARGET_METHODS.contains(name);
                boolean tick = TICK_METHODS.contains(name);
                return new MethodVisitor(Opcodes.ASM9) {
                    private final List<Number> recent = new ArrayList<>(4);
                    private int timeWindow = 0;

                    private void remember(Number n) {
                        recent.add(n);
                        if (recent.size() > 4) recent.remove(0);
                    }

                    private void seen() {
                        if (timeWindow > 0) timeWindow--;
                    }

                    @Override
                    public void visitInsn(int opcode) {
                        seen();
                    }

                    @Override
                    public void visitVarInsn(int opcode, int var) {
                        seen();
                    }

                    @Override
                    public void visitLdcInsn(Object value) {
                        seen();
                        if (target && value instanceof Number n) remember(n);
                    }

                    @Override
                    public void visitIntInsn(int opcode, int operand) {
                        seen();
                        if (target && (opcode == Opcodes.BIPUSH || opcode == Opcodes.SIPUSH)) remember(operand);
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String mName, String mDesc, boolean itf) {
                        seen();
                        // 时间源：视为“接下来几条指令处于时间比较上下文”
                        if (("java/lang/System".equals(owner) && ("currentTimeMillis".equals(mName) || "nanoTime".equals(mName)))
                                || ("net/minecraft/Util".equals(owner) && "getMillis".equals(mName))
                                || ("net/minecraft/world/level/Level".equals(owner) && "getGameTime".equals(mName))) {
                            timeWindow = 4;
                        }
                        if (target && "java/lang/Math".equals(owner) && ("min".equals(mName) || "max".equals(mName))) {
                            for (Number n : recent) {
                                double v = n.doubleValue();
                                if (v > 0 && v != 1 && v < 1.0e9 && !Double.isNaN(v)) {
                                    String type = n instanceof Float ? "float"
                                        : n instanceof Double ? "double"
                                        : n instanceof Long ? "long" : "int";
                                    p.caps.add(new DefenseProfile.CapCandidate(name, v, type));
                                }
                            }
                            recent.clear();
                        }
                    }

                    @Override
                    public void visitFieldInsn(int opcode, String owner, String fName, String desc) {
                        seen();
                        boolean isRead = opcode == Opcodes.GETFIELD;
                        // tickCount 作为时间源
                        if (isRead && "tickCount".equals(fName)) {
                            timeWindow = 4;
                        }
                        if (target) {
                            if ("Z".equals(desc) && isRead) {
                                owners.putIfAbsent(fName, owner);
                                descs.putIfAbsent(fName, desc);
                                boolRead.add(fName);
                            } else if ("I".equals(desc) || "J".equals(desc) || "F".equals(desc) || "D".equals(desc)) {
                                owners.putIfAbsent(fName, owner);
                                descs.putIfAbsent(fName, desc);
                                targetRead.add(fName);
                                if (timeWindow > 0) timeRead.add(fName);
                                if (FIELD_HINT.matcher(fName).matches()) {
                                    p.fields.add(new DefenseProfile.FieldCandidate(owner, fName, desc));
                                }
                            }
                            // hurt 里被写：命中时刷新的“最后受击/计时器”或无敌标志
                            if (!isRead) {
                                if ("I".equals(desc) || "J".equals(desc) || "F".equals(desc) || "D".equals(desc)) {
                                    hitWritten.add(fName);
                                } else if ("Z".equals(desc)) {
                                    hitWrittenBool.add(fName);
                                }
                            }
                        }
                        if (tick && !isRead
                                && ("I".equals(desc) || "J".equals(desc) || "F".equals(desc) || "D".equals(desc))) {
                            tickWritten.add(fName);
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

        // 结构门：time / timer / bool
        for (String n : timeRead) {
            p.gates.add(new DefenseProfile.FieldCandidate(owners.get(n), n, descs.get(n), "time"));
        }
        for (String n : targetRead) {
            if (tickWritten.contains(n)) {
                p.gates.add(new DefenseProfile.FieldCandidate(owners.get(n), n, descs.get(n), "timer"));
            }
        }
        for (String n : boolRead) {
            p.gates.add(new DefenseProfile.FieldCandidate(owners.get(n), n, descs.get(n), "bool"));
        }
        for (String n : hitWritten) {
            if (targetRead.contains(n)) {
                p.gates.add(new DefenseProfile.FieldCandidate(owners.get(n), n, descs.get(n), "hitwritten"));
            }
        }
        for (String n : hitWrittenBool) {
            p.gates.add(new DefenseProfile.FieldCandidate(owners.get(n), n, descs.get(n), "bool"));
        }
        return p;
    }
}
