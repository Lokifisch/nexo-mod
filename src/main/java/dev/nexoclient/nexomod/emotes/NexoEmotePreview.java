package dev.nexoclient.nexomod.emotes;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * An animation file read just far enough to pose a model in a menu, which is not a
 * player the animation library can drive: body-part rotations, straight lines
 * between keyframes, no easing or movement. No Minecraft imports, so
 * {@code tools/emote-preview-check} can run it with plain {@code javac}.
 * Only for files {@link NexoEmoteFileCheck} has passed.
 */
public final class NexoEmotePreview {
	private static final float PAUSE_SECONDS = 0.6F;
	private static final float[] STILL = {0F, 0F, 0F};

	private final Map<String, TreeMap<Float, float[]>> bones = new HashMap<>();
	private float length;
	private boolean loop;
	private float[] thumbnail;

	private NexoEmotePreview() {
	}

	public static NexoEmotePreview parse(byte[] data) {
		return parse(data, null);
	}

	/** One animation out of a file holding several, like the starters'; null or a missing name takes the first. */
	public static NexoEmotePreview parse(byte[] data, String name) {
		JsonObject animations = JsonParser.parseString(new String(data, StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("animations");
		JsonObject animation = name != null && animations.has(name) ? animations.getAsJsonObject(name)
				: animations.entrySet().iterator().next().getValue().getAsJsonObject();
		NexoEmotePreview preview = new NexoEmotePreview();
		preview.loop = animation.has("loop") && animation.get("loop").isJsonPrimitive() && animation.get("loop").getAsJsonPrimitive().isBoolean() && animation.get("loop").getAsBoolean();
		float last = 0F;
		for (Map.Entry<String, JsonElement> bone : animation.getAsJsonObject("bones").entrySet()) {
			JsonElement rotation = bone.getValue().getAsJsonObject().get("rotation");
			if (rotation == null) {
				continue;
			}
			TreeMap<Float, float[]> frames = new TreeMap<>();
			if (rotation.isJsonObject()) {
				for (Map.Entry<String, JsonElement> frame : rotation.getAsJsonObject().entrySet()) {
					frames.put(Float.parseFloat(frame.getKey()), vector(frame.getValue()));
				}
				if (frames.isEmpty()) {
					continue;
				}
				last = Math.max(last, frames.lastKey());
			} else {
				frames.put(0F, vector(rotation));
			}
			preview.bones.put(normal(bone.getKey()), frames);
		}
		preview.length = animation.has("animation_length") ? animation.get("animation_length").getAsFloat() : last;
		return preview;
	}

	/** {@code right_arm}, {@code rightArm} and {@code torso} all name the same parts as the model. */
	private static String normal(String bone) {
		String name = bone.replace("_", "").toLowerCase(Locale.ROOT);
		return name.equals("torso") ? "body" : name;
	}

	private static float[] vector(JsonElement value) {
		if (value.isJsonObject()) {
			JsonObject frame = value.getAsJsonObject();
			return vector(frame.has("post") ? frame.get("post") : frame.has("vector") ? frame.get("vector") : frame.get("pre"));
		}
		if (value.isJsonArray()) {
			JsonArray list = value.getAsJsonArray();
			return new float[] {list.get(0).getAsFloat(), list.get(1).getAsFloat(), list.get(2).getAsFloat()};
		}
		float all = value.getAsFloat();
		return new float[] {all, all, all};
	}

	/** Where to be {@code seconds} into an endless preview: one that doesn't loop rests between plays. */
	public float position(float seconds) {
		if (length <= 0F) {
			return 0F;
		}
		float at = seconds % (loop ? length : length + PAUSE_SECONDS);
		return Math.min(at, length);
	}

	/** Rotation in degrees, with the same signs as the game's model parts. */
	public float[] rotation(String bone, float position) {
		TreeMap<Float, float[]> frames = bones.get(bone.replace("_", "").toLowerCase(Locale.ROOT));
		if (frames == null) {
			return STILL;
		}
		Map.Entry<Float, float[]> before = frames.floorEntry(position);
		Map.Entry<Float, float[]> after = frames.ceilingEntry(position);
		if (before == null || after == null || before.getKey().equals(after.getKey())) {
			return (before != null ? before : after).getValue();
		}
		float t = (position - before.getKey()) / (after.getKey() - before.getKey());
		float[] out = new float[3];
		for (int i = 0; i < 3; i++) {
			out[i] = before.getValue()[i] + (after.getValue()[i] - before.getValue()[i]) * t;
		}
		return out;
	}

	/** The arms at the most expressive moment, for a card: {right x, right z, left x, left z} in radians. */
	public float[] thumbnail() {
		if (thumbnail != null) {
			return thumbnail;
		}
		float best = -1F;
		float at = 0F;
		for (int i = 0; i <= 32; i++) {
			float position = length * i / 32F;
			float[] right = rotation("right_arm", position);
			float[] left = rotation("left_arm", position);
			float reach = Math.abs(right[0]) + Math.abs(right[2]) + Math.abs(left[0]) + Math.abs(left[2]);
			if (reach > best) {
				best = reach;
				at = position;
			}
		}
		float[] right = rotation("right_arm", at);
		float[] left = rotation("left_arm", at);
		float radians = (float) (Math.PI / 180D);
		thumbnail = new float[] {right[0] * radians, right[2] * radians, left[0] * radians, left[2] * radians};
		return thumbnail;
	}
}
