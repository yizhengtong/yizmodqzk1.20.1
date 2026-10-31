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
 *   <li>被覆写的生命/伤害方法（hurt/actuallyHurt/setHealth/getHealth/isInvulnerableTo/die/...）；</li>
 *   <li>{@code hurt}/{@code actuallyHurt}/{@code setHealth} 内、{@code Math.min/max} 附近的数值常量
 *       （疑似单次伤害上限 / 时间窗上限）；</li>
 *   <li>上述方法内读写的数值字段（疑似上限/冷却/无敌帧字段）。</li>
 * </ul>
 *
 * <p>不修改任何字节码；结果写入 {@code config/yizmodqzk/defense_profiles.json} 并内存缓存。
 * 真正的改写由 {@link DefensePatcher}（P2，开关控制）执行。</p>
 */
public final class DefenseScanner {

    private static final Set<String> TARGET_METHODS = Set.of("hurt", "actuallyHurt", "setHealth");
    private static final Set<String> TRACKED_METHODS = Set.of(
        "hurt", "actuallyHurt", "setHealth", "getHealth", "isInvulnerableTo",
        "die", "isDeadOrDying", "isAlive");

    /** 字段名提示（仅作为“候选”，真正使用前还会看它是否出现在 target 方法里）。 */
    private static final java.util.regex.Pattern FIELD_HINT = java.util.regex.Pattern.compile(
        "(?i).*(invul|iframe|hurt|damage|cap|limit|max|cooldown|tick).*");

    private DefenseScanner() {}

    private static final Set<String> SCANNED = ConcurrentHashMap.newKeySet();
    private static volatile long LAST_SAVE = 0L;
    private static final Path FILE = FMLPaths.CONFIGDIR.get().resolve("yizmodqzk/defense_profiles.json");
    private static volatile boolean loaded = false;

    /** 首次使用时从磁盘加载缓存。 */
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
            // 逐层扫描（子类 → 父类 → ... → Entity）：很多模组把 hurt/setHealth 的上限写在上层抽象类
            for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
                DefenseProfile p = scanClass(c);
                if (p == null) continue;
                merged.classHash ^= p.classHash;
                merged.overrides.addAll(p.overrides);
                for (DefenseProfile.CapCandidate cap : p.caps) {
                    boolean dup = false;
                    for (DefenseProfile.CapCandidate x : merged.caps) {
                        if (x.method.equals(cap.method) && x.value == cap.value && x.type.equals(cap.type)) {
                            dup = true;
                            break;
                        }
                    }
                    if (!dup) merged.caps.add(cap);
                }
                for (DefenseProfile.FieldCandidate f : p.fields) {
                    boolean dup = false;
                    for (DefenseProfile.FieldCandidate x : merged.fields) {
                        if (x.owner.equals(f.owner) && x.name.equals(f.name) && x.desc.equals(f.desc)) {
                            dup = true;
                            break;
                        }
                    }
                    if (!dup) merged.fields.add(f);
                }
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

    /** 单类扫描 + 缓存（供 ensureScanned 合并父类用）。 */
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

    public static DefenseProfile scan(Class<?> cls) {
        if (cls == null) return null;
        try {
            byte[] bytes = readBytes(cls);
            return bytes == null ? null : scan(cls.getName(), bytes);
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

        ClassReader cr = new ClassReader(bytes);
        cr.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] ex) {
                if (TRACKED_METHODS.contains(name)) p.overrides.add(name);
                boolean track = TARGET_METHODS.contains(name);
                return new MethodVisitor(Opcodes.ASM9) {
                    private final List<Number> recent = new ArrayList<>(4);

                    private void remember(Number n) {
                        recent.add(n);
                        if (recent.size() > 4) recent.remove(0);
                    }

                    @Override
                    public void visitLdcInsn(Object value) {
                        if (track && value instanceof Number n) remember(n);
                    }

                    @Override
                    public void visitIntInsn(int opcode, int operand) {
                        if (track && (opcode == Opcodes.BIPUSH || opcode == Opcodes.SIPUSH)) remember(operand);
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String mName, String desc, boolean itf) {
                        if (track && "java/lang/Math".equals(owner) && ("min".equals(mName) || "max".equals(mName))) {
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
                        if (!track) return;
                        if (!"I".equals(desc) && !"J".equals(desc) && !"F".equals(desc) && !"D".equals(desc)) return;
                        if (FIELD_HINT.matcher(fName).matches()) {
                            p.fields.add(new DefenseProfile.FieldCandidate(owner, fName, desc));
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return p;
    }
}
