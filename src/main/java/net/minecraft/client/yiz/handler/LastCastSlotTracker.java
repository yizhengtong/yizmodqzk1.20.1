package net.minecraft.client.yiz.handler;

/** 临时记录最近一次技能施法的槽位（供 onCast 内部读取）。 */
public final class LastCastSlotTracker {

    private static final ThreadLocal<Integer> SLOT = new ThreadLocal<>();

    private LastCastSlotTracker() {}

    public static void set(int slot) { SLOT.set(slot); }
    public static int get() { Integer v = SLOT.get(); return v != null ? v : -1; }
    public static void clear() { SLOT.remove(); }
}
