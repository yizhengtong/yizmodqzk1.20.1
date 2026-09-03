package net.minecraft.client.yiz.core;

import sun.misc.Unsafe;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.item.Item;

/**
 * Pure-Java vtable method replacement using {@code sun.misc.Unsafe}.
 *
 * <p>Directly overwrites HotSpot internal {@code Method} entry points
 * ({@code _from_interpreted_entry} / {@code _from_compiled_entry})
 * to redirect virtual method dispatch — without ASM, Agent, bytecode
 * modification, or class redefinition.</p>
 *
 * <h3>How it works</h3>
 * <ol>
 *   <li>Get the {@code Klass*} from an instance's object header (same
 *       technique as {@link PlayerClassSwapper})</li>
 *   <li>Locate the vtable within the {@code InstanceKlass} structure</li>
 *   <li>Use Java reflection to compute the vtable index of the target
 *       method, then read the {@code Method*} pointer at that slot</li>
 *   <li>Copy donor's {@code _from_interpreted_entry} and
 *       {@code _from_compiled_entry} to the target {@code Method*}</li>
 * </ol>
 *
 * <p>All JVM-structure offsets are determined at class-init time via
 * empirical probing — no hardcoded offsets. ConstMethod/Symbol internals
 * are deliberately NOT consulted: HotSpot stores method names indirectly
 * via ConstantPool indices, making direct Symbol scanning unreliable.</p>
 */
@SuppressWarnings("removal")
public final class VTableReplace {

    private static final Unsafe U;
    private static final long KLASS_OFFSET;
    private static final boolean KLASS_COMPRESSED;

    // ── InstanceKlass vtable fields ──────────────────────────
    /** Offset of {@code _vtable_len} from klass base */
    static long VTABLE_LEN_OFFSET = -1;
    /** Offset of first vtable entry from klass base */
    static long VTABLE_BASE_OFFSET = -1;
    /** Number of vtable entries inherited from {@link Object}, computed in Phase 1.
     *  Used by {@code getOwnVTableIndex} to skip Object's vtable slots. */
    static int OBJECT_VTABLE_METHODS = -1;

    // ── Method structure fields ──────────────────────────────
    /** Offset of {@code _from_interpreted_entry} from Method* */
    static long METHOD_FROM_INTERPRETED_OFFSET = -1;
    /** Offset of {@code _from_compiled_entry} from Method* */
    static long METHOD_FROM_COMPILED_OFFSET = -1;

    // ── Donor cache ──────────────────────────────────────────
    /** method-descriptor → donor entry-point addresses */
    private static final Map<String, long[]> DONOR_CACHE = new ConcurrentHashMap<>();
    /** Donor klass address */
    private static long donorKlassAddr;

    // ── Narrow klass decode ──────────────────────────────────
    /** Descompression base for narrow klass pointers */
    static long NARROW_KLASS_BASE;
    /** Descompression shift for narrow klass pointers (0 = no compression) */
    static int NARROW_KLASS_SHIFT;
    /** Reference klass address used to compute the safe metaspace range.
     *  Set in Phase 1 after we successfully extract a known-good klass. */
    static long METASPACE_REFERENCE = 0;

    // ── State ────────────────────────────────────────────────
    private static volatile boolean initialized;
    private static volatile boolean probesPassed;
    private static String initError;

    static {
        U = getUnsafe();
        KLASS_OFFSET = determineKlassOffset();
        KLASS_COMPRESSED = isCompressedKlass();
        NARROW_KLASS_BASE = 0;
        NARROW_KLASS_SHIFT = 0;

        if (KLASS_COMPRESSED) {
            // Try to resolve narrow klass encoding by reading the hidden
            // full-Klass* field from java.lang.Class objects on the heap.
            // HotSpot injects a 64-bit Klass* into each Class mirror object.
            if (resolveNarrowKlassFromClassObject()) {
                try {
                    probeOffsets();
                    probesPassed = true;
                    System.out.println("[VTableReplace] VTable replacement available");
                } catch (Exception e) {
                    initError = e.getMessage();
                    probesPassed = false;
                    System.err.println("[VTableReplace] WARNING: " + initError);
                }
            } else {
                probesPassed = false;
                initError = "Compressed klass pointers detected, resolution failed. " +
                        "VTable replacement unavailable.";
                System.err.println("[VTableReplace] " + initError);
            }
        } else {
            try {
                probeOffsets();
                probesPassed = true;
            } catch (Exception e) {
                initError = e.getMessage();
                probesPassed = false;
                System.err.println("[VTableReplace] WARNING: offset probing failed: " + initError);
                System.err.println("[VTableReplace] vtable replacement will be disabled");
            }
        }
        initialized = true;
    }

    private VTableReplace() {}

    // ══════════════════════════════════════════════════════════
    //  Public API
    // ══════════════════════════════════════════════════════════

    /**
     * Returns whether the probing succeeded and vtable replacement is available.
     */
    public static boolean isAvailable() {
        return initialized && probesPassed;
    }

    /**
     * Returns the initialization error message, or null if probing passed.
     */
    public static String getInitError() {
        return initError;
    }

    // ══════════════════════════════════════════════════════════
    //  Diagnostics
    // ══════════════════════════════════════════════════════════

    /**
     * Returns whether donors have been initialized ({@link #initDonors()} was called).
     */
    public static boolean isDonorsInitialized() {
        return donorKlassAddr != 0;
    }

    /**
     * Returns the number of cached donor method entries.
     */
    public static int getDonorCacheSize() {
        return DONOR_CACHE.size();
    }

    /**
     * Returns a human-readable summary of all probed offsets.
     */
    public static String getProbeOffsetsSummary() {
        if (!initialized) return "VTableReplace not yet initialized";
        if (!probesPassed) return "Probes failed: " + (initError != null ? initError : "unknown");

        StringBuilder sb = new StringBuilder();
        sb.append("§6VTableReplace §aAVAILABLE§r\n");
        sb.append("  §7Klass offset:§r ").append(KLASS_OFFSET).append(" (").append(KLASS_COMPRESSED ? "compressed" : "full").append(")\n");
        sb.append("  §7VTable len offset:§r 0x").append(Long.toHexString(VTABLE_LEN_OFFSET)).append("\n");
        sb.append("  §7VTable base offset:§r 0x").append(Long.toHexString(VTABLE_BASE_OFFSET)).append("\n");
        sb.append("  §7Object vtable methods:§r ").append(OBJECT_VTABLE_METHODS).append("\n");
        sb.append("  §7_interpreted off:§r ").append(METHOD_FROM_INTERPRETED_OFFSET).append("\n");
        sb.append("  §7_compiled off:§r ").append(METHOD_FROM_COMPILED_OFFSET).append("\n");
        sb.append("  §7Donors initialized:§r ").append(isDonorsInitialized()).append("\n");
        sb.append("  §7Donor cache size:§r ").append(getDonorCacheSize());
        return sb.toString();
    }

    /**
     * Diagnose a class by checking whether each named method is overridden
     * relative to {@link Item} base class.
     */
    public static String diagnoseItemMethods(Class<?> itemClass, String[] methodNames, String[] methodDescs) {
        if (!isAvailable()) return "§cVTableReplace not available: " + initError;

        try {
            Object itemPhantom = U.allocateInstance(Item.class);
            long itemKlass = getKlass(itemPhantom);
            Object phantom = U.allocateInstance(itemClass);
            long klass = getKlass(phantom);

            StringBuilder sb = new StringBuilder();
            sb.append("§6VTable diagnosis for §e").append(itemClass.getSimpleName()).append("§r\n");

            for (int i = 0; i < methodNames.length; i++) {
                int idx = resolveVTableIndex(itemClass, methodNames[i], methodDescs[i]);
                if (idx < 0) {
                    sb.append("  §c").append(methodNames[i]).append(": §rNOT FOUND\n");
                    continue;
                }
                long subPtr = U.getLong(klass + VTABLE_BASE_OFFSET + (long) idx * 8);
                long basePtr = U.getLong(itemKlass + VTABLE_BASE_OFFSET + (long) idx * 8);
                boolean isOverride = subPtr != basePtr;
                sb.append("  §a").append(methodNames[i]).append(": §rFOUND")
                        .append(isOverride ? " §e(OVERRIDDEN)" : " §7(inherited)")
                        .append(" idx=").append(idx).append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return "§cDiagnostic failed: " + e.getMessage();
        }
    }

    /**
     * Initialize donor cache. Call once after Minecraft classes are loaded.
     * Forces JIT compilation of donor methods so compiled entry points exist.
     */
    public static void initDonors() {
        if (!isAvailable()) return;

        EmptyImplementations.forceJit();

        try {
            Object phantom = U.allocateInstance(EmptyImplementations.class);
            donorKlassAddr = getKlass(phantom);
            cacheDonorMethods();
        } catch (Exception e) {
            System.err.println("[VTableReplace] Donor init failed: " + e.getMessage());
        }
    }

    /**
     * Replace a method in the given class with a no-op implementation.
     *
     * @param targetClass the class whose method to replace
     * @param methodName  JVM method name (e.g. "setHealth", "kill")
     * @param methodDesc  JVM method descriptor (e.g. "(F)V", "()V")
     * @return true if the replacement succeeded
     */
    public static boolean replaceMethod(Class<?> targetClass, String methodName, String methodDesc) {
        if (!isAvailable()) {
            System.err.println("[VTableReplace] Not available: " + initError);
            return false;
        }
        if (donorKlassAddr == 0) {
            System.err.println("[VTableReplace] Donor not initialized, call initDonors() first");
            return false;
        }

        try {
            // Find target's vtable index for this method via reflection.
            int idx = resolveVTableIndex(targetClass, methodName, methodDesc);
            if (idx < 0) {
                System.err.println("[VTableReplace] Method not found: " +
                        targetClass.getName() + "." + methodName + methodDesc);
                return false;
            }

            Object phantom = U.allocateInstance(targetClass);
            long targetKlass = getKlass(phantom);
            long targetMethod = U.getLong(targetKlass + VTABLE_BASE_OFFSET + (long) idx * 8);
            if (!isValidMetaPointer(targetMethod)) {
                System.err.println("[VTableReplace] Target Method* invalid at idx=" + idx);
                return false;
            }

            long[] donorEntryPoints = DONOR_CACHE.get(methodDesc);
            if (donorEntryPoints == null) {
                System.err.println("[VTableReplace] No donor for descriptor: " + methodDesc);
                return false;
            }

            long oldInterpreted = writeEntryPoints(targetMethod, donorEntryPoints);

            System.out.println("[VTableReplace] Replaced " +
                    targetClass.getSimpleName() + "." + methodName + methodDesc +
                    " interpreted_entry: " + Long.toHexString(oldInterpreted) +
                    " → " + Long.toHexString(donorEntryPoints[0]));
            return true;

        } catch (Exception e) {
            System.err.println("[VTableReplace] replaceMethod failed: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Convenience: replace by method name and parameter types.
     * The descriptor is derived from the parameter types and void return.
     */
    public static boolean replaceVoidMethod(Class<?> targetClass, String methodName,
                                            Class<?>... paramTypes) {
        String desc = buildDescriptor(void.class, paramTypes);
        return replaceMethod(targetClass, methodName, desc);
    }

    /**
     * Replace a method in {@code targetClass} with the implementation from
     * {@code sourceClass} — effectively "un-overriding" the method.
     *
     * <p>Copies {@code _from_interpreted_entry} and {@code _from_compiled_entry}
     * from the source Method* to the target Method*. After this call, virtual
     * dispatch through the target's vtable slot executes the source class's
     * implementation instead of the target's override.</p>
     *
     * @param targetClass the class whose method entry to replace
     * @param methodName  JVM method name
     * @param methodDesc  JVM method descriptor
     * @param sourceClass the class providing the replacement entry points
     * @return true if the replacement succeeded
     */
    public static boolean replaceMethodFromSource(Class<?> targetClass,
                                                   String methodName, String methodDesc,
                                                   Class<?> sourceClass) {
        if (!isAvailable()) {
            System.err.println("[VTableReplace] Not available: " + initError);
            return false;
        }

        try {
            // Resolve vtable index from the source class — it's the canonical
            // declaring class of the method (the override slot in target maps
            // to the same index because HotSpot reuses parent slots for overrides).
            int idx = resolveVTableIndex(sourceClass, methodName, methodDesc);
            if (idx < 0) {
                System.err.println("[VTableReplace] Method not found in source: " +
                        sourceClass.getName() + "." + methodName + methodDesc);
                return false;
            }

            Object targetPhantom = U.allocateInstance(targetClass);
            long targetKlass = getKlass(targetPhantom);
            long targetMethod = U.getLong(targetKlass + VTABLE_BASE_OFFSET + (long) idx * 8);
            if (!isValidMetaPointer(targetMethod)) {
                System.err.println("[VTableReplace] Target Method* invalid at idx=" + idx);
                return false;
            }

            Object sourcePhantom = U.allocateInstance(sourceClass);
            long sourceKlass = getKlass(sourcePhantom);
            long sourceMethod = U.getLong(sourceKlass + VTABLE_BASE_OFFSET + (long) idx * 8);
            if (!isValidMetaPointer(sourceMethod)) {
                System.err.println("[VTableReplace] Source Method* invalid at idx=" + idx);
                return false;
            }

            // If they point to the same Method*, the target didn't override it
            if (targetMethod == sourceMethod) {
                System.out.println("[VTableReplace] Not overridden in " +
                        targetClass.getSimpleName() + "." + methodName +
                        " — already inherits from " + sourceClass.getSimpleName());
                return true;
            }

            long[] sourceEntryPoints = readEntryPoints(sourceMethod);
            long oldInterpreted = writeEntryPoints(targetMethod, sourceEntryPoints);

            System.out.println("[VTableReplace] Copied " +
                    sourceClass.getSimpleName() + "." + methodName + methodDesc +
                    " → " + targetClass.getSimpleName() + "." + methodName +
                    " interpreted_entry: " + Long.toHexString(oldInterpreted) +
                    " → " + Long.toHexString(sourceEntryPoints[0]));
            return true;

        } catch (Exception e) {
            System.err.println("[VTableReplace] replaceMethodFromSource failed: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    // ══════════════════════════════════════════════════════════
    //  Reflection → vtable index resolution
    // ══════════════════════════════════════════════════════════

    /**
     * Find the {@link Method} on {@code clazz} whose name and JVM descriptor
     * match. Walks the class hierarchy from {@code clazz} up to {@link Object},
     * returning the highest match (i.e., the canonical declaring class for
     * the override chain).
     */
    static Method findReflectionMethod(Class<?> clazz, String name, String desc) {
        Class<?> declaring = null;
        Method found = null;
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(name)) continue;
                if (!buildDescriptor(m.getReturnType(), m.getParameterTypes()).equals(desc)) continue;
                if (Modifier.isStatic(m.getModifiers())) continue;
                if (Modifier.isPrivate(m.getModifiers())) continue;
                // Take the topmost (closest to Object) declaring class because
                // HotSpot puts the vtable slot at the parent's position when
                // the method is an override.
                declaring = c;
                found = m;
            }
        }
        return found != null ? findInDeclaringClass(declaring, name, desc) : null;
    }

    private static Method findInDeclaringClass(Class<?> c, String name, String desc) {
        for (Method m : c.getDeclaredMethods()) {
            if (!m.getName().equals(name)) continue;
            if (!buildDescriptor(m.getReturnType(), m.getParameterTypes()).equals(desc)) continue;
            if (Modifier.isStatic(m.getModifiers())) continue;
            if (Modifier.isPrivate(m.getModifiers())) continue;
            return m;
        }
        return null;
    }

    /**
     * Compute the vtable index of {@code (name, desc)} on {@code clazz}.
     *
     * <p>Strategy: find the canonical declaring class along the inheritance
     * chain (the class farthest from {@code clazz} that still declares the
     * method). The vtable index is the parent vtable length plus the
     * method's position among the declaring class's own virtual methods.</p>
     *
     * <p>HotSpot reuses parent slots for overrides, so the same index works
     * for both the declaring class and any subclass that overrides it.</p>
     *
     * @return -1 if the method cannot be located.
     */
    static int resolveVTableIndex(Class<?> clazz, String name, String desc) {
        // Find the topmost declaring class for (name, desc) in the chain.
        Class<?> declaring = null;
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            if (findInDeclaringClass(c, name, desc) != null) {
                declaring = c;
            }
        }
        if (declaring == null) return -1;

        // Position of the method among declaring's own virtual methods.
        List<Method> own = ownVirtualMethods(declaring);
        int posInOwn = -1;
        for (int i = 0; i < own.size(); i++) {
            Method m = own.get(i);
            if (!m.getName().equals(name)) continue;
            if (!buildDescriptor(m.getReturnType(), m.getParameterTypes()).equals(desc)) continue;
            posInOwn = i;
            break;
        }
        if (posInOwn < 0) return -1;

        // vtable index = parent vtable length + position among own virtuals.
        int parentLen = parentVTableLength(declaring);
        if (parentLen < 0) return -1;
        return parentLen + posInOwn;
    }

    /**
     * Return the class's own (declared) virtual methods in source-declaration
     * order, filtered to match HotSpot vtable layout: no static, no private,
     * no synthetic, no bridge methods.
     */
    private static List<Method> ownVirtualMethods(Class<?> c) {
        Method[] decl = c.getDeclaredMethods();
        List<Method> list = new ArrayList<>(decl.length);
        for (Method m : decl) {
            int mods = m.getModifiers();
            if (Modifier.isStatic(mods)) continue;
            if (Modifier.isPrivate(mods)) continue;
            if (m.isSynthetic()) continue;
            if (m.isBridge()) continue;
            list.add(m);
        }
        // HotSpot lays out own methods in source-declaration order; getDeclaredMethods()
        // returns them in unspecified order but typically class-file order. Sort by
        // (name, descriptor) as a tie-breaker to make behavior deterministic when
        // override-only methods stay at the parent's slot index. The actual vtable
        // position depends on the class file's method_info table order which mirrors
        // source order — for own non-override methods this matters; overrides take
        // their slot from the parent regardless.
        return list;
    }

    /**
     * Compute the vtable length of {@code clazz}'s superclass via a phantom
     * instance. Returns {@link #OBJECT_VTABLE_METHODS} if the parent is Object.
     */
    private static int parentVTableLength(Class<?> clazz) {
        Class<?> parent = clazz.getSuperclass();
        if (parent == null || parent == Object.class) return OBJECT_VTABLE_METHODS;
        try {
            Object phantom = U.allocateInstance(parent);
            long klass = getKlass(phantom);
            return countConsecutivePtrs(klass + VTABLE_BASE_OFFSET);
        } catch (Throwable t) {
            return -1;
        }
    }

    // ══════════════════════════════════════════════════════════
    //  VTable navigation (legacy helpers, used by diagnostics)
    // ══════════════════════════════════════════════════════════

    static long getVTableBase(long klassAddr) {
        return klassAddr + VTABLE_BASE_OFFSET;
    }

    // ══════════════════════════════════════════════════════════
    //  Method struct reading
    // ══════════════════════════════════════════════════════════

    /**
     * Read a MetaspaceObj pointer (Method*, ConstMethod*, Symbol*).
     * These are ALWAYS full 64-bit pointers — never compressed, even when
     * UseCompressedClassPointers is enabled.
     */
    static long readMetaPtr(long addr) {
        return U.getLong(addr);
    }

    // ══════════════════════════════════════════════════════════
    //  Entry point manipulation
    // ══════════════════════════════════════════════════════════

    /** Read both entry points from a Method*: [interpretedEntry, compiledEntry]. */
    static long[] readEntryPoints(long methodPtr) {
        long interpreted = U.getLong(methodPtr + METHOD_FROM_INTERPRETED_OFFSET);
        long compiled = U.getLong(methodPtr + METHOD_FROM_COMPILED_OFFSET);
        return new long[]{interpreted, compiled};
    }

    /** Overwrite entry points on a Method*. Returns the old interpreted entry. */
    static long writeEntryPoints(long methodPtr, long[] entryPoints) {
        long old = U.getLong(methodPtr + METHOD_FROM_INTERPRETED_OFFSET);
        U.putLong(methodPtr + METHOD_FROM_INTERPRETED_OFFSET, entryPoints[0]);
        U.putLong(methodPtr + METHOD_FROM_COMPILED_OFFSET, entryPoints[1]);
        return old;
    }

    // ══════════════════════════════════════════════════════════
    //  Donor caching
    // ══════════════════════════════════════════════════════════

    private static void cacheDonorMethods() {
        String[][] donorMethods = {
                {"emptyVoid", "()V"},
                {"emptyFloat", "(F)V"},
                {"emptyInt", "(I)V"},
                {"emptyObject", "(Ljava/lang/Object;)V"},
                {"emptyBool", "()Z"},
                {"emptyDamageSourceBoolean", "(Lnet/minecraft/world/damagesource/DamageSource;)Z"},
                {"emptyDamageSourceFloat", "(Lnet/minecraft/world/damagesource/DamageSource;F)Z"},
                {"emptyDie", "(Lnet/minecraft/world/damagesource/DamageSource;)V"},
                {"emptyRemove", "(Lnet/minecraft/world/entity/Entity$RemovalReason;)V"},
                {"emptyZeroInt", "()I"},
                {"emptyReturnFirstFloat", "(FF)F"},
                {"emptyIdentityFloat", "(F)F"},
        };

        for (String[] entry : donorMethods) {
            String name = entry[0];
            String desc = entry[1];
            int idx = resolveVTableIndex(EmptyImplementations.class, name, desc);
            if (idx < 0) {
                System.err.println("[VTableReplace] Donor method index lookup failed: " + name + desc);
                continue;
            }
            long methodPtr = U.getLong(donorKlassAddr + VTABLE_BASE_OFFSET + (long) idx * 8);
            if (!isValidMetaPointer(methodPtr)) {
                System.err.println("[VTableReplace] Donor Method* invalid: " + name + desc +
                        " at idx=" + idx);
                continue;
            }
            long[] eps = readEntryPoints(methodPtr);
            DONOR_CACHE.put(desc, eps);
            System.out.println("[VTableReplace] Cached donor: " + name + desc +
                    " idx=" + idx +
                    " i=" + Long.toHexString(eps[0]) +
                    " c=" + Long.toHexString(eps[1]));
        }
    }

    // ══════════════════════════════════════════════════════════
    //  Offset probing
    // ══════════════════════════════════════════════════════════

    // Probe classes for vtable detection
    @SuppressWarnings("unused")
    static class VTableProbeBase {
        public void probeBaseA() {}
        public void probeBaseB() {}
        public void probeBaseC() {}
    }
    @SuppressWarnings("unused")
    static class VTableProbeSub extends VTableProbeBase {
        public void probeSubA() {}
        public void probeSubB() {}
    }

    // Probe class for entry-point detection
    @SuppressWarnings("unused")
    static class MethodProbeA {
        public void XYZW_METHOD_PROBE_A_12345() {}
        public void XYZW_METHOD_PROBE_SIG_FLOAT_67890(float x) {}
    }

    private static final String PROBE_NAME_A = "XYZW_METHOD_PROBE_A_12345";

    private static void probeOffsets() {
        // ── Phase 1: Find vtable base + length offset + Object's vtable size ──
        probeVTable();

        // ── Phase 2: Find entry point offsets in Method ──
        probeEntryPointOffsets();

        System.out.println("[VTableReplace] All probes passed:");
        System.out.println("  VTABLE_LEN_OFFSET     = " + VTABLE_LEN_OFFSET);
        System.out.println("  VTABLE_BASE_OFFSET    = " + VTABLE_BASE_OFFSET);
        System.out.println("  OBJECT_VTABLE_METHODS = " + OBJECT_VTABLE_METHODS);
        System.out.println("  FROM_INTERPRETED_OFF  = " + METHOD_FROM_INTERPRETED_OFFSET);
        System.out.println("  FROM_COMPILED_OFF     = " + METHOD_FROM_COMPILED_OFFSET);
    }

    private static void probeVTable() {
        Object base = new VTableProbeBase();
        Object sub = new VTableProbeSub();
        long baseKlass = getKlass(base);
        long subKlass = getKlass(sub);

        // Anchor metaspace range: anything more than ~64GB away from a known
        // klass is almost certainly not a valid Method*/ConstMethod*/Symbol*.
        METASPACE_REFERENCE = baseKlass;

        System.out.println("[VTableReplace] probeVTable: baseKlass=0x" +
                Long.toHexString(baseKlass) + " subKlass=0x" +
                Long.toHexString(subKlass));

        for (long off = 0; off < 0x800; off += 8) {
            int baseCount = countConsecutivePtrs(baseKlass + off);
            int subCount = countConsecutivePtrs(subKlass + off);

            if (baseCount >= 5 && baseCount <= 500
                    && subCount == baseCount + 2) {
                VTABLE_BASE_OFFSET = off;
                // VTableProbeBase declares 3 own virtual methods, so Object
                // contributes (baseCount - 3) vtable slots.
                OBJECT_VTABLE_METHODS = baseCount - 3;
                if (U.getInt(baseKlass + off - 4) == baseCount) {
                    VTABLE_LEN_OFFSET = off - 4;
                } else if (U.getInt(baseKlass + off - 8) == baseCount) {
                    VTABLE_LEN_OFFSET = off - 8;
                } else {
                    VTABLE_LEN_OFFSET = off - 4;
                }

                System.out.println("[VTableReplace] Found vtable at base_off=0x" +
                        Long.toHexString(off) + " len_off=0x" +
                        Long.toHexString(VTABLE_LEN_OFFSET) +
                        " baseCount=" + baseCount + " subCount=" + subCount +
                        " objectMethods=" + OBJECT_VTABLE_METHODS);
                return;
            }
        }

        throw new RuntimeException("Cannot find vtable in InstanceKlass");
    }

    /** Count consecutive valid Metaspace pointers starting at the given address. */
    private static int countConsecutivePtrs(long addr) {
        int count = 0;
        for (int i = 0; i < 500; i++) {
            long ptr = U.getLong(addr + (long) i * 8);
            if (ptr == 0) break;
            if (!isValidMetaPointer(ptr)) break;
            count++;
        }
        return count;
    }

    private static void probeEntryPointOffsets() {
        // Force MethodProbeA to fully initialize so its vtable is filled.
        try {
            new MethodProbeA();
            new MethodProbeA().XYZW_METHOD_PROBE_A_12345();
            new MethodProbeA().XYZW_METHOD_PROBE_SIG_FLOAT_67890(0f);
        } catch (Throwable ignored) {}

        // Use the Method* for a probe method, and compare before/after JIT.
        // Before JIT: _from_compiled_entry is usually 0
        // After JIT: it becomes a valid code-cache address
        // The _from_interpreted_entry is always valid (interpreter stub).

        long methodPtr = findMethodInVTableByProbeName(MethodProbeA.class);
        if (methodPtr == 0) {
            throw new RuntimeException("Cannot find probe method for entry point probing");
        }

        long[] before = new long[20];
        for (int i = 0; i < 20; i++) {
            before[i] = U.getLong(methodPtr + (long) i * 8);
        }

        // Force JIT
        MethodProbeA real = new MethodProbeA();
        for (int i = 0; i < 15_000; i++) {
            real.XYZW_METHOD_PROBE_A_12345();
        }

        long[] after = new long[20];
        for (int i = 0; i < 20; i++) {
            after[i] = U.getLong(methodPtr + (long) i * 8);
        }

        long compiledOffset = -1;
        long interpretedOffset = -1;

        for (int i = 0; i < 20; i++) {
            long offset = i * 8;
            if (before[i] == 0 && after[i] != 0 && isCodeAddress(after[i])) {
                compiledOffset = offset;
            }
            if (before[i] != 0 && isCodeAddress(before[i]) && before[i] == after[i]) {
                if (interpretedOffset < 0 || offset < interpretedOffset) {
                    interpretedOffset = offset;
                }
            }
        }

        if (compiledOffset < 0) {
            for (int i = 0; i < 20; i++) {
                long offset = i * 8;
                if (isCodeAddress(before[i])) {
                    interpretedOffset = offset;
                    if (i + 1 < 20 && before[i + 1] == 0) {
                        compiledOffset = (i + 1) * 8;
                    } else if (i + 2 < 20 && before[i + 2] == 0) {
                        compiledOffset = (i + 2) * 8;
                    }
                    break;
                }
            }
        }

        if (interpretedOffset < 0 || compiledOffset < 0) {
            interpretedOffset = 0x38;
            compiledOffset = 0x48;
            System.err.println("[VTableReplace] WARNING: entry point probe failed, " +
                    "using JDK 21 defaults");
        }

        METHOD_FROM_INTERPRETED_OFFSET = interpretedOffset;
        METHOD_FROM_COMPILED_OFFSET = compiledOffset;
        System.out.println("[VTableReplace] Found interpreted_entry at " +
                interpretedOffset + " compiled_entry at " + compiledOffset);
    }

    /**
     * Find the first own (non-Object) Method* in the given probe class's vtable.
     * Used by Phase 2 (entry point probing). Walks the vtable via
     * {@link #countConsecutivePtrs} so it does not depend on the per-klass
     * {@code _vtable_len} field, which is unreliable across InstanceKlass
     * layouts.
     */
    private static long findMethodInVTableByProbeName(Class<?> probeClass) {
        try {
            Object phantom = U.allocateInstance(probeClass);
            long klassAddr = getKlass(phantom);
            long vtableBase = klassAddr + VTABLE_BASE_OFFSET;

            int objMethods = OBJECT_VTABLE_METHODS;
            if (objMethods < 0) {
                System.err.println("[VTableReplace] probe lookup: OBJECT_VTABLE_METHODS not set");
                return 0;
            }

            int vtableLen = countConsecutivePtrs(vtableBase);
            if (objMethods >= vtableLen) {
                System.err.println("[VTableReplace] probe lookup: " + probeClass.getSimpleName() +
                        " has no own methods in vtable");
                return 0;
            }
            return U.getLong(vtableBase + (long) objMethods * 8);
        } catch (Throwable t) {
            System.err.println("[VTableReplace] probe lookup: " + t.getMessage());
            return 0;
        }
    }

    // ══════════════════════════════════════════════════════════
    //  Pointer validation heuristics
    // ══════════════════════════════════════════════════════════

    static boolean isValidMetaPointer(long ptr) {
        if (ptr == 0 || ptr == 0xFFFFFFFFFFFFFFFFL) return false;
        if (ptr <= 0x1000L || ptr >= 0x800000000000L) return false;
        if ((ptr & 0x7L) != 0) return false;
        if (METASPACE_REFERENCE != 0) {
            long delta = ptr - METASPACE_REFERENCE;
            if (delta < -0x1_0000_0000L || delta > 0x10_0000_0000L) return false;
        }
        return true;
    }

    static boolean isCodeAddress(long addr) {
        return addr != 0
                && addr > 0x100000L
                && addr < 0x800000000000L;
    }

    // ══════════════════════════════════════════════════════════
    //  Descriptor builder
    // ══════════════════════════════════════════════════════════

    static String buildDescriptor(Class<?> returnType, Class<?>... paramTypes) {
        StringBuilder sb = new StringBuilder("(");
        for (Class<?> p : paramTypes) {
            sb.append(classToDescriptor(p));
        }
        sb.append(")");
        sb.append(classToDescriptor(returnType));
        return sb.toString();
    }

    static String classToDescriptor(Class<?> c) {
        if (c == void.class) return "V";
        if (c == boolean.class) return "Z";
        if (c == byte.class) return "B";
        if (c == char.class) return "C";
        if (c == short.class) return "S";
        if (c == int.class) return "I";
        if (c == long.class) return "J";
        if (c == float.class) return "F";
        if (c == double.class) return "D";
        if (c.isArray()) return "[" + classToDescriptor(c.getComponentType());
        return "L" + c.getName().replace('.', '/') + ";";
    }

    // ══════════════════════════════════════════════════════════
    //  Klass pointer utilities (same technique as PlayerClassSwapper)
    // ══════════════════════════════════════════════════════════

    /**
     * Get the FULL (decompressed) klass address from an object's header.
     */
    private static long getKlass(Object obj) {
        long full = U.getLong(obj, KLASS_OFFSET);
        if ((full & 0xFFFFFFFF00000000L) != 0) {
            return full;
        }
        int narrow = (int) full;
        if (NARROW_KLASS_SHIFT != 0 || NARROW_KLASS_BASE != 0) {
            return decompressKlass(narrow);
        }
        return narrow & 0xFFFFFFFFL;
    }

    static long decompressKlass(int narrow) {
        long narrowUnsigned = narrow & 0xFFFFFFFFL;
        if (NARROW_KLASS_SHIFT == 0) {
            return NARROW_KLASS_BASE + narrowUnsigned;
        }
        return (narrowUnsigned << NARROW_KLASS_SHIFT) + NARROW_KLASS_BASE;
    }

    /**
     * Resolve narrow klass base by reading the hidden full Klass* field
     * injected into every {@code java.lang.Class} mirror object.
     */
    private static boolean resolveNarrowKlassFromClassObject() {
        try {
            Object probeObj = new Object();
            int narrowKlass = U.getInt(probeObj, KLASS_OFFSET);
            if (narrowKlass <= 0) return false;

            Class<?> objectClass = Object.class;
            for (long off = 16; off < 256; off += 8) {
                try {
                    long val = U.getLong(objectClass, off);
                    if (val > 0x700000000000L && val < 0x800000000000L) {
                        long base = val - (((long) narrowKlass & 0xFFFFFFFFL) << 3);
                        if ((base & 0xFFF) == 0) {
                            NARROW_KLASS_BASE = base;
                            NARROW_KLASS_SHIFT = 3;
                            System.out.println("[VTableReplace] Found full Klass* at " +
                                    "Class offset " + off + ": 0x" + Long.toHexString(val) +
                                    " base=0x" + Long.toHexString(base));
                            return true;
                        }
                    }
                } catch (Exception ignored) {}
            }

            System.err.println("[VTableReplace] Could not find full Klass* in Class object. " +
                    "Scanned 0x10-0x100, narrow=0x" + Integer.toHexString(narrowKlass));
        } catch (Exception e) {
            System.err.println("[VTableReplace] Class-object scan failed: " + e.getMessage());
        }
        return false;
    }

    private static boolean isCompressedKlass() {
        Object probe = new Object();
        long full = U.getLong(probe, KLASS_OFFSET);
        return (full & 0xFFFFFFFF00000000L) == 0 && (int) full != 0;
    }

    private static Unsafe getUnsafe() {
        try {
            var c = Unsafe.class.getDeclaredConstructor();
            c.setAccessible(true);
            return c.newInstance();
        } catch (Exception e1) {
            try {
                Field f = Unsafe.class.getDeclaredField("theUnsafe");
                f.setAccessible(true);
                return (Unsafe) f.get(null);
            } catch (Exception e2) {
                throw new ExceptionInInitializerError(e2);
            }
        }
    }

    private static long determineKlassOffset() {
        Object probe = new Object();
        long markWord = U.getLong(probe, 0L);

        for (long off : new long[]{8L, 12L, 16L, 4L}) {
            try {
                int maybeKlass = U.getInt(probe, off);
                if (maybeKlass != 0
                        && (maybeKlass & 0xFFFFFFFFL) != (markWord & 0xFFFFFFFFL)) {
                    return off;
                }
            } catch (Exception ignored) {}
        }
        return 8L;
    }
}
