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
 * <p>骨骼固定为 {@link #BONES} 八根；{@code right_item} / {@code left_item} 是左右手"物品点"，
 * 驱动对应手上物品相对手心的额外偏移/旋转，物品本身仍由原版挂在手上（不重绘、不隐藏）。</p>
 *
 * <p>时间单位秒；关键帧时间升序；采样时相邻帧之间 smoothstep。</p>
 */
public final class AnimClip {

    public static final List<String> BONES = List.of(
            "head", "body", "right_arm", "left_arm", "right_leg", "left_leg",
            "right_item", "left_item");

    public record Keyframe(float time, BoneTransform transform) {}

    private String name;
    private float duration;
    private float minDuration;
    private boolean loop;
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
        bones.forEach((bone, frames) -> {
            for (Keyframe frame : frames) {
                copy.setKeyframe(bone, frame.time(), frame.transform());
            }
        });
        return copy;
    }

    public JsonObject toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("name", name);
        root.addProperty("duration", duration);
        root.addProperty("minDuration", minDuration);
        root.addProperty("loop", loop);
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
