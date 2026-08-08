package net.minecraft.client.yiz.editor;

/**
 * 破限附魔跨 Mixin 通信工具。
 * Player.attack() 中 @ModifyArg 存入预期伤害，LivingEntity.hurt() 中读取并恢复。
 */
public final class PoxianDamageTracker {

    private PoxianDamageTracker() {}

    private static final ThreadLocal<Float> EXPECTED = new ThreadLocal<>();

    public static void set(float expected) {
        EXPECTED.set(expected);
    }

    public static Float get() {
        return EXPECTED.get();
    }

    public static void clear() {
        EXPECTED.remove();
    }
}
