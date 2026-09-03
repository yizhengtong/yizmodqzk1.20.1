package net.minecraft.client.yiz.core;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;

/**
 * Donor class providing no-op method implementations for vtable replacement.
 *
 * <p>Each method here has the exact same JVM descriptor as the Minecraft
 * method it's meant to replace. {@link VTableReplace} extracts the Method*
 * from this class's vtable and overwrites the target's vtable entry,
 * redirecting all virtual dispatch through the original vtable slot to
 * these empty implementations.</p>
 *
 * <p>Instances are never created via normal construction — only via
 * {@code Unsafe.allocateInstance()} for klass pointer extraction.</p>
 */
public class EmptyImplementations {

    // ── void ()V ────────────────────────────────────────────
    /** Descriptor ()V — replaces kill(), discard() */
    public void emptyVoid() {}

    // ── void (F)V ───────────────────────────────────────────
    /** Descriptor (F)V — replaces setHealth(float) */
    public void emptyFloat(float f) {}

    // ── void (I)V ───────────────────────────────────────────
    /** Descriptor (I)V */
    @SuppressWarnings("unused")
    public void emptyInt(int x) {}

    // ── void (Ljava/lang/Object;)V ──────────────────────────
    /** Descriptor (Ljava/lang/Object;)V */
    @SuppressWarnings("unused")
    public void emptyObject(Object x) {}

    // ── boolean ()Z ─────────────────────────────────────────
    /** Descriptor ()Z — replaces isAlive(), isDeadOrDying(), hurt() */
    public boolean emptyBool() { return false; }

    // ── boolean (Lnet/minecraft/world/damagesource/DamageSource;)Z ──
    /** Descriptor (Lnet/minecraft/world/damagesource/DamageSource;)Z — replaces hurt(DamageSource) */
    @SuppressWarnings("unused")
    public boolean emptyDamageSourceBoolean(DamageSource source) { return false; }

    // ── boolean (Lnet/minecraft/world/damagesource/DamageSource;F)Z ──
    /** Descriptor (Lnet/minecraft/world/damagesource/DamageSource;F)Z — replaces hurt(DamageSource, float) */
    @SuppressWarnings("unused")
    public boolean emptyDamageSourceFloat(DamageSource source, float amount) { return false; }

    // ── void (Lnet/minecraft/world/damagesource/DamageSource;)V ──
    /** Descriptor (Lnet/minecraft/world/damagesource/DamageSource;)V — replaces die(DamageSource) */
    @SuppressWarnings("unused")
    public void emptyDie(DamageSource source) {}

    // ── void (Lnet/minecraft/world/entity/Entity$RemovalReason;)V ──
    /** Descriptor (Lnet/minecraft/world/entity/Entity$RemovalReason;)V — replaces remove(RemovalReason) */
    @SuppressWarnings("unused")
    public void emptyRemove(Entity.RemovalReason reason) {}

    // ── int ()I ─────────────────────────────────────────────
    /** Descriptor ()I — returns 0; replaces getArmorValue(), getUseDuration() */
    @SuppressWarnings("unused")
    public int emptyZeroInt() { return 0; }

    // ── float (FF)F ─────────────────────────────────────────
    /** Descriptor (FF)F — returns first arg (damage unchanged); replaces getDamageAfterMagicAbsorb() */
    @SuppressWarnings("unused")
    public float emptyReturnFirstFloat(float damage, float protection) { return damage; }

    // ── float (F)F ──────────────────────────────────────────
    /** Descriptor (F)F — returns the arg unchanged; identity for float transformers */
    @SuppressWarnings("unused")
    public float emptyIdentityFloat(float value) { return value; }

    // ── Force-JIT helpers ────────────────────────────────────
    /**
     * Call each method many times to trigger JIT compilation, ensuring
     * valid compiled entry points exist before we extract them.
     */
    public static void forceJit() {
        EmptyImplementations donor = new EmptyImplementations();
        for (int i = 0; i < 10_000; i++) {
            donor.emptyVoid();
            donor.emptyFloat((float) i);
            donor.emptyInt(i);
            if (i % 100 == 0) {
                donor.emptyObject(donor);
                donor.emptyBool();
                donor.emptyDamageSourceBoolean(null);
                donor.emptyDamageSourceFloat(null, i);
                donor.emptyDie(null);
                donor.emptyRemove(null);
                donor.emptyZeroInt();
                donor.emptyReturnFirstFloat((float) i, (float) i);
                donor.emptyIdentityFloat((float) i);
            }
        }
    }
}
