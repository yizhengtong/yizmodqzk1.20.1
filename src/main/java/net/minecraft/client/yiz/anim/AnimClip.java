package net.minecraft.client.yiz.anim;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一段骨骼动画：时长、最小时长、每根骨骼的关键帧。
 *
 * <p>骨骼分两套：{@link #THIRD_PERSON_BONES}（第三人称身体/四肢/左右手物品点）与
 * {@link #FIRST_PERSON_BONES}（第一人称左右手/左右手物品点）。同一段动画可以同时带两套，
 * 播放时按当前视角各取一套。</p>
 *
 * <p>物品点 {@code right_item}/{@code left_item}（第三人称）与 {@code fp_right_item}/{@code fp_left_item}
 * （第一人称）驱动对应手上物品相对手心的额外偏移/旋转，物品本身仍由原版挂在手上（不重绘、不隐藏）。</p>
 *
 * <p>时间单位秒；关键帧时间升序；采样时相邻帧之间 smoothstep。</p>
 */
public final class AnimClip {

    /** 第三人称骨骼（第三视角模型）。 */
    public static final List<String> THIRD_PERSON_BONES = List.of(
            "head", "body", "right_arm", "left_arm", "right_leg", "left_leg",
            "right_item", "left_item");

    /** 第一人称骨骼（第一视角手臂/物品）。 */
    public static final List<String> FIRST_PERSON_BONES = List.of(
            "fp_right_arm", "fp_left_arm", "fp_right_item", "fp_left_item");

    /** 全部骨骼（存储用）。 */
    public static final List<String> BONES;

    static {
        List<String> all = new ArrayList<>(THIRD_PERSON_BONES);
        all.addAll(FIRST_PERSON_BONES);
        BONES = List.copyOf(all);
    }

    public record Keyframe(float time, BoneTransform transform) {}

    private String name;
    private float duration;
    private float minDuration;
    private boolean loop;
    /**
     * 这段动画是否启用第一人称：默认 false（没导入过 Blockbench 姿态 → 第一人称保持原版）。
     * 只有通过「导入FP姿态」或物品点下方的姿态按钮写入过 fp_* 关键帧才会置 true。
     * 旧动画文件没有这个字段，一律按 false 处理，避免历史 fp 关键帧影响第一人称。
     */
    private boolean firstPerson;
    private final Map<String, List<Keyframe>> bones = new LinkedHashMap<>();

    public AnimClip(String name, float duration, float minDuration, boolean loop) {
        this.name = name;
        this.duration = Math.max(0.05F, duration);
        this.minDuration = Math.max(0.01F, Math.min(minDuration, this.duration));
        this.loop = loop;
        for (String bone : BONES) {
            this.bones.put(bone, new ArrayList<>());
        }
    }

    public String name() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public float duration() {
        return duration;
    }

    public void setDuration(float duration) {
        this.duration = Math.max(0.05F, duration);
        this.minDuration = Math.min(this.minDuration, this.duration);
    }

    public float minDuration() {
        return minDuration;
    }

    public void setMinDuration(float minDuration) {
        this.minDuration = Math.max(0.01F, Math.min(minDuration, duration));
    }

    public boolean loop() {
        return loop;
    }

    public void setLoop(boolean loop) {
        this.loop = loop;
    }

    public List<Keyframe> keyframes(String bone) {
        List<Keyframe> frames = bones.get(bone);
        return frames == null ? List.of() : List.copyOf(frames);
    }

    /** 这根骨骼在这段动画里是否有（哪怕一个）关键帧；没有的骨骼保持原版姿态。 */
    public boolean hasKeyframes(String bone) {
        List<Keyframe> frames = bones.get(bone);
        return frames != null && !frames.isEmpty();
    }

    public Map<String, List<Keyframe>> allKeyframes() {
        Map<String, List<Keyframe>> copy = new LinkedHashMap<>();
        bones.forEach((bone, frames) -> copy.put(bone, List.copyOf(frames)));
        return copy;
    }

    /** 采样：无关键帧 → ZERO；超出范围取端值；中间 smoothstep。 */
    public BoneTransform sample(String bone, float time) {
        List<Keyframe> frames = bones.get(bone);
        if (frames == null || frames.isEmpty()) {
            return BoneTransform.ZERO;
        }
        if (time <= frames.get(0).time()) {
            return frames.get(0).transform();
        }
        int last = frames.size() - 1;
        if (time >= frames.get(last).time()) {
            return frames.get(last).transform();
        }
        for (int i = 0; i < last; i++) {
            Keyframe a = frames.get(i);
            Keyframe b = frames.get(i + 1);
            if (time >= a.time() && time <= b.time()) {
                float span = b.time() - a.time();
                float t = span <= 1.0E-6F ? 1F : (time - a.time()) / span;
                return BoneTransform.smooth(a.transform(), b.transform(), t);
            }
        }
        return frames.get(last).transform();
    }

    public void setKeyframe(String bone, float time, BoneTransform transform) {
        List<Keyframe> frames = bones.computeIfAbsent(bone, k -> new ArrayList<>());
        float clamped = Math.max(0F, Math.min(duration, time));
        for (int i = 0; i < frames.size(); i++) {
            if (Math.abs(frames.get(i).time() - clamped) < 0.001F) {
                frames.set(i, new Keyframe(clamped, transform));
                return;
            }
        }
        frames.add(new Keyframe(clamped, transform));
        frames.sort(Comparator.comparingDouble(Keyframe::time));
    }

    public boolean removeKeyframe(String bone, float time) {
        List<Keyframe> frames = bones.get(bone);
        return frames != null && frames.removeIf(frame -> Math.abs(frame.time() - time) < 0.001F);
    }

    /** 清空某根骨骼的全部关键帧（第一人称姿态导入时整体覆盖用）。 */
    public void clearKeyframes(String bone) {
        List<Keyframe> frames = bones.get(bone);
        if (frames != null) {
            frames.clear();
        }
    }

    public boolean hasKeyframeAt(String bone, float time) {
        List<Keyframe> frames = bones.get(bone);
        if (frames == null) {
            return false;
        }
        for (Keyframe frame : frames) {
            if (Math.abs(frame.time() - time) < 0.001F) {
                return true;
            }
        }
        return false;
    }

    public AnimClip copy() {
        AnimClip copy = new AnimClip(name, duration, minDuration, loop);
        copy.firstPerson = this.firstPerson;
        bones.forEach((bone, frames) -> {
            for (Keyframe frame : frames) {
                copy.setKeyframe(bone, frame.time(), frame.transform());
            }
        });
        return copy;
    }

    /** 这段动画是否启用第一人称（导入过姿态才为 true）。 */
    public boolean firstPersonEnabled() {
        return firstPerson;
    }

    public void setFirstPerson(boolean enabled) {
        this.firstPerson = enabled;
    }

    public JsonObject toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("name", name);
        root.addProperty("duration", duration);
        root.addProperty("minDuration", minDuration);
        root.addProperty("loop", loop);
        root.addProperty("first_person", firstPerson);
        JsonObject bonesJson = new JsonObject();
        bones.forEach((bone, frames) -> {
            if (frames.isEmpty()) {
                return;
            }
            JsonArray array = new JsonArray();
            for (Keyframe frame : frames) {
                BoneTransform t = frame.transform();
                JsonObject f = new JsonObject();
                f.addProperty("time", frame.time());
                f.addProperty("rx", t.rx());
                f.addProperty("ry", t.ry());
                f.addProperty("rz", t.rz());
                f.addProperty("tx", t.tx());
                f.addProperty("ty", t.ty());
                f.addProperty("tz", t.tz());
                f.addProperty("sx", t.sx());
                f.addProperty("sy", t.sy());
                f.addProperty("sz", t.sz());
                array.add(f);
            }
            bonesJson.add(bone, array);
        });
        root.add("bones", bonesJson);
        return root;
    }

    public static AnimClip fromJson(JsonObject root) {
        String name = root.has("name") ? root.get("name").getAsString() : "unnamed";
        float duration = root.has("duration") ? root.get("duration").getAsFloat() : 0.3F;
        float minDuration = root.has("minDuration") ? root.get("minDuration").getAsFloat() : Math.min(0.1F, duration);
        boolean loop = root.has("loop") && root.get("loop").getAsBoolean();
        AnimClip clip = new AnimClip(name, duration, minDuration, loop);
        clip.firstPerson = root.has("first_person") && root.get("first_person").getAsBoolean();
        if (!root.has("bones") || !root.get("bones").isJsonObject()) {
            return clip;
        }
        for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("bones").entrySet()) {
            if (!BONES.contains(entry.getKey())) {
                continue;
            }
            for (JsonElement element : entry.getValue().getAsJsonArray()) {
                JsonObject f = element.getAsJsonObject();
                BoneTransform t = new BoneTransform(
                        get(f, "rx", 0F), get(f, "ry", 0F), get(f, "rz", 0F),
                        get(f, "tx", 0F), get(f, "ty", 0F), get(f, "tz", 0F),
                        get(f, "sx", 1F), get(f, "sy", 1F), get(f, "sz", 1F));
                clip.setKeyframe(entry.getKey(), get(f, "time", 0F), t);
            }
        }
        return clip;
    }

    private static float get(JsonObject object, String key, float fallback) {
        return object.has(key) ? object.get(key).getAsFloat() : fallback;
    }
}
