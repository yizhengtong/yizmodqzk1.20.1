package net.minecraft.client.yiz.anim;

/**
 * 一段动画的播放实例。
 *
 * <p>正常游戏里 {@code requestedSeconds} 就是 {@link AnimClip#duration()}（编辑器里填写的时长），
 * 因此 {@code speed = 1}，整条时间轴按作者设定的速度播完；参数保留是为了兼容服务端下发的
 * 显式时长，并做 {@code [minDuration, duration]} 的上下限保护。</p>
 */
public final class AnimPlayer {

    private final AnimClip clip;
    private final long startNanos;
    private final float playSeconds;
    private final float speed;
    private final boolean loop;

    public AnimPlayer(AnimClip clip, float requestedSeconds) {
        this.clip = clip;
        this.playSeconds = Math.max(clip.minDuration(), Math.min(requestedSeconds, clip.duration()));
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
