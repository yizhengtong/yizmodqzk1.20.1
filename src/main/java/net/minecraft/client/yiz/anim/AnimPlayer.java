package net.minecraft.client.yiz.anim;

/**
 * 一段动画的播放实例。
 *
 * <p>播放时长不再固定为编辑器里填写的 {@code duration}，而是由攻击间隔决定
 * （服务端/客户端都取玩家当前主手物品的 {@code getCurrentItemAttackStrengthDelay()}）。
 * 整条时间轴会按 {@code duration / playSeconds} 缩放：攻击越快动画播得越快，
 * 攻击越慢动画播得越慢，保证每次攻击都在下一次攻击前播完。</p>
 */
public final class AnimPlayer {

    /**
     * 最小播放时长：0.3 秒。
     * 模组里 1.8 风格的剑会把攻击间隔压到接近 0（无冷却），动画也不能短于这个时长。
     */
    private static final float MIN_PLAY_SECONDS = 0.3F;

    /** 动画整体播放速度倍率（2.0 = 比攻击间隔快一倍）。 */
    private static final float SPEED_MULTIPLIER = 2.0F;

    private final AnimClip clip;
    private final long startNanos;
    private final float playSeconds;
    private final float speed;
    private final boolean loop;

    public AnimPlayer(AnimClip clip, float requestedSeconds) {
        this.clip = clip;
        this.playSeconds = Math.max(MIN_PLAY_SECONDS, requestedSeconds / SPEED_MULTIPLIER);
        this.speed = clip.duration() / this.playSeconds;
        this.loop = clip.loop();
        this.startNanos = System.nanoTime();
    }

    public AnimClip clip() {
        return clip;
    }

    public float timeSeconds() {
        float elapsed = (System.nanoTime() - startNanos) / 1_000_000_000.0F;
        float time = elapsed * speed;
        return loop ? time % clip.duration() : Math.min(time, clip.duration());
    }

    public boolean finished() {
        if (loop) {
            return false;
        }
        return (System.nanoTime() - startNanos) / 1_000_000_000.0F >= playSeconds;
    }

    public BoneTransform sample(String bone) {
        return clip.sample(bone, timeSeconds());
    }
}
