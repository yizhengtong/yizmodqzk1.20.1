package net.minecraft.client.yiz.anim;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端动画状态：正在播放的玩家 + 播放队列 + 编辑器预览。
 *
 * <h2>播放规则</h2>
 * <ul>
 *   <li>服务端每攻击一次就下发一段动画，客户端<b>不会打断</b>当前段，而是排到队尾；</li>
 *   <li>当前段按自己的 {@code duration}（编辑器填写的时长）播完，才从队首接下一段；</li>
 *   <li>换段在同一帧内完成（{@link #get(int)} 里顺手接上），不会闪原版姿态；</li>
 *   <li>实体死亡/移除、切维度、退出世界时，正在播的和排队的都会清掉。</li>
 * </ul>
 *
 * <p>采样入口 {@link #sample(int, String)} 预览优先，然后播放态，最后原版姿态。
 * 编辑器视口渲染的是 {@code mc.player} 实体，走的也是这里。</p>
 */
public final class AnimClientState {

    /** 单个实体最多排队几段，防止狂点把队列堆成几十秒。 */
    private static final int MAX_PENDING = 8;

    private static final Map<Integer, AnimPlayer> ACTIVE = new ConcurrentHashMap<>();
    /** 等待当前动画播完的后续动画，按收到顺序排列。 */
    private static final Map<Integer, Deque<Pending>> PENDING = new ConcurrentHashMap<>();

    /**
     * 第一人称独立播放态：只由本地攻击触发、只喂给第一人称物品渲染，
     * 和上面服务端下发的第三人称播放态完全隔离（互不影响）。
     */
    private static volatile AnimPlayer firstPersonActive;
    private static final Deque<Pending> FIRST_PERSON_PENDING = new ArrayDeque<>();

    private static volatile AnimClip previewClip;
    private static volatile float previewTime;
    private static volatile net.minecraft.world.level.Level lastLevel;

    private AnimClientState() {}

    /** 排队记录：动画 + 这一段该播多久（服务端下发，等于 {@code clip.duration()}）。 */
    private record Pending(AnimClip clip, float playSeconds) {}

    /**
     * 收到服务端的一段动画：当前没有在播就直接开始，否则排到队尾等待。
     * 队列不会因为"下一击"被清空或打断，所以每一段都会完整播完。
     */
    public static synchronized void start(int entityId, AnimClip clip, float playSeconds) {
        if (clip == null) {
            return;
        }
        // 每次攻击都丢弃未播放的旧动画，从 0 开始播新的，避免连点后队列积压
        PENDING.remove(entityId);
        ACTIVE.put(entityId, new AnimPlayer(clip, playSeconds));
    }

    /**
     * 当前正在播的动画；如果刚好播完，会立刻把队首的下一段接上。
     * 这样换段发生在同一帧内，不会出现一帧原版姿态的闪烁。
     */
    public static AnimPlayer get(int entityId) {
        AnimPlayer player = ACTIVE.get(entityId);
        if (player == null) {
            return startNext(entityId);
        }
        if (!player.finished()) {
            return player;
        }
        ACTIVE.remove(entityId, player);
        return startNext(entityId);
    }

    /** 把队首的下一段变成当前段；没有排队则返回 null。 */
    private static AnimPlayer startNext(int entityId) {
        Deque<Pending> queue = PENDING.get(entityId);
        if (queue == null) {
            return null;
        }
        Pending next = queue.pollFirst();
        if (queue.isEmpty()) {
            PENDING.remove(entityId, queue);
        }
        if (next == null) {
            return null;
        }
        AnimPlayer player = new AnimPlayer(next.clip(), next.playSeconds());
        ACTIVE.put(entityId, player);
        return player;
    }

    public static boolean isPlaying(int entityId) {
        return get(entityId) != null;
    }

    /** 当前生效的动画（编辑器预览优先），没有则 null；供动画应用器判断哪些骨骼该用绝对姿态。 */
    public static AnimClip currentClip(int entityId) {
        AnimClip clip = previewClip;
        if (clip != null) {
            return clip;
        }
        AnimPlayer player = get(entityId);
        return player == null ? null : player.clip();
    }

    /** 当前动画是否给某根骨骼写了关键帧（第一人称骨骼据此决定是否接管原版动作）。 */
    public static boolean hasKeyframes(int entityId, String bone) {
        AnimClip clip = currentClip(entityId);
        return clip != null && clip.hasKeyframes(bone);
    }

    public static BoneTransform sample(int entityId, String bone) {
        AnimClip clip = previewClip;
        if (clip != null) {
            return clip.sample(bone, previewTime);
        }
        AnimPlayer player = get(entityId);
        return player == null ? BoneTransform.ZERO : player.sample(bone);
    }

    public static boolean anyPlaying() {
        return previewClip != null || !ACTIVE.isEmpty() || !PENDING.isEmpty();
    }

    /**
     * 本地第一人称攻击：开始/排队一段第一人称动画。
     * 和第三人称队列规则一致——当前段播完再接下段，不打断。
     */
    public static synchronized void startFirstPerson(AnimClip clip, float playSeconds) {
        if (clip == null || !clip.firstPersonEnabled()) {
            // 没有导入过第一人称姿态的动画不参与第一人称，保持原版
            return;
        }
        // 每次攻击都清空未播放的旧动画，从 0 开始播新的
        FIRST_PERSON_PENDING.clear();
        firstPersonActive = new AnimPlayer(clip, playSeconds);
    }

    /** 当前正在播的第一人称动画；播完会立刻接队首。 */
    public static AnimPlayer getFirstPerson() {
        AnimPlayer player = firstPersonActive;
        if (player == null) {
            return startNextFirstPerson();
        }
        if (!player.finished()) {
            return player;
        }
        firstPersonActive = null;
        return startNextFirstPerson();
    }

    private static AnimPlayer startNextFirstPerson() {
        Pending next = FIRST_PERSON_PENDING.pollFirst();
        if (next == null) {
            return null;
        }
        AnimPlayer player = new AnimPlayer(next.clip(), next.playSeconds());
        firstPersonActive = player;
        return player;
    }

    /** 第一人称当前生效的动画（编辑器预览优先）。 */
    public static AnimClip currentFirstPersonClip() {
        AnimClip clip = previewClip;
        if (clip != null) {
            return clip;
        }
        AnimPlayer player = getFirstPerson();
        return player == null ? null : player.clip();
    }

    /** 第一人称动画是否给某根骨骼写了关键帧（只有导入过姿态的动画才算）。 */
    public static boolean hasFirstPersonKeyframes(String bone) {
        AnimClip clip = currentFirstPersonClip();
        return clip != null && clip.firstPersonEnabled() && clip.hasKeyframes(bone);
    }

    /** 第一人称采样：预览优先，然后本地播放态；没有则返回原版姿态。 */
    public static BoneTransform sampleFirstPerson(String bone) {
        AnimClip clip = previewClip;
        if (clip != null) {
            return clip.sample(bone, previewTime);
        }
        AnimPlayer player = getFirstPerson();
        return player == null ? BoneTransform.ZERO : player.sample(bone);
    }

    /** 第一人称是否有动画在播（含预览）。 */
    public static boolean anyFirstPersonPlaying() {
        return previewClip != null || firstPersonActive != null || !FIRST_PERSON_PENDING.isEmpty();
    }

    public static void setPreview(AnimClip clip, float time) {
        previewClip = clip;
        previewTime = Math.max(0F, time);
    }

    public static void clearPreview() {
        previewClip = null;
        previewTime = 0F;
    }

    public static boolean isPreviewing() {
        return previewClip != null;
    }

    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            clear();
            return;
        }
        if (lastLevel != mc.level) {
            ACTIVE.clear();
            PENDING.clear();
            clearFirstPerson();
            lastLevel = mc.level;
        }
        for (Map.Entry<Integer, AnimPlayer> entry : ACTIVE.entrySet()) {
            int entityId = entry.getKey();
            Entity entity = mc.level.getEntity(entityId);
            if (entity == null || entity.isRemoved() || !entity.isAlive()) {
                ACTIVE.remove(entityId);
                PENDING.remove(entityId);
            } else if (entry.getValue().finished()) {
                ACTIVE.remove(entityId);
                startNext(entityId);
            }
        }
        // 保险：队列不应该脱离当前动画单独存在
        PENDING.keySet().removeIf(entityId -> !ACTIVE.containsKey(entityId));

        // 第一人称本地播放态：死亡/退出时清空，否则播完接下一段
        if (mc.player == null || !mc.player.isAlive()) {
            clearFirstPerson();
        } else if (firstPersonActive != null && firstPersonActive.finished()) {
            firstPersonActive = null;
            startNextFirstPerson();
        }
    }

    public static void clear() {
        ACTIVE.clear();
        PENDING.clear();
        clearFirstPerson();
        lastLevel = null;
    }

    private static void clearFirstPerson() {
        firstPersonActive = null;
        FIRST_PERSON_PENDING.clear();
    }
}
