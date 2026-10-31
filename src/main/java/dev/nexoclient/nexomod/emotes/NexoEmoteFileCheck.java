package dev.nexoclient.nexomod.emotes;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * A downloaded animation is plain keyframes and nothing else: no Molang, sounds,
 * particles or bone parents, which the animation library would act on for everyone
 * who sees the emote. The client's copy of {@code EmoteService/src/nexo_emotes/validate.py},
 * minus the checks that only matter to whoever is uploading.
 */
public final class NexoEmoteFileCheck {
	private static final Set<String> TOP = Set.of("format_version", "animations");
	private static final Set<String> ANIMATION = Set.of("animation_length", "loop", "bones", "start_delay", "loop_delay", "override_previous_animation");
	private static final Map<String, Double> CHANNELS = Map.of("rotation", 7200.0, "position", 64.0, "scale", 8.0);
	private static final Set<String> FRAME = Set.of("pre", "post", "vector", "lerp_mode", "easing", "easingArg", "easingArgs");

	private NexoEmoteFileCheck() {
	}

	public static boolean safe(byte[] data) {
		try {
			JsonObject doc = JsonParser.parseString(new String(data, StandardCharsets.UTF_8)).getAsJsonObject();
			if (!TOP.containsAll(doc.keySet())) {
				return false;
			}
			JsonObject animations = doc.getAsJsonObject("animations");
			if (animations.size() != 1) {
				return false;
			}
			JsonObject animation = animations.entrySet().iterator().next().getValue().getAsJsonObject();
			if (!ANIMATION.containsAll(animation.keySet())) {
				return false;
			}
			for (Map.Entry<String, JsonElement> bone : animation.getAsJsonObject("bones").entrySet()) {
				for (Map.Entry<String, JsonElement> channel : bone.getValue().getAsJsonObject().entrySet()) {
					Double limit = CHANNELS.get(channel.getKey());
					if (limit == null || !channelSafe(channel.getValue(), limit)) {
						return false;
					}
				}
			}
			return true;
		} catch (RuntimeException e) {
			return false;
		}
	}

	private static boolean channelSafe(JsonElement channel, double limit) {
		if (!channel.isJsonObject()) {
			return vectorSafe(channel, limit);
		}
		for (Map.Entry<String, JsonElement> frame : channel.getAsJsonObject().entrySet()) {
			double time = Double.parseDouble(frame.getKey());
			if (!(time >= 0.0 && time <= 3600.0)) {
				return false;
			}
			if (!frame.getValue().isJsonObject()) {
				if (!vectorSafe(frame.getValue(), limit)) {
					return false;
				}
				continue;
			}
			JsonObject fields = frame.getValue().getAsJsonObject();
			if (!FRAME.containsAll(fields.keySet())) {
				return false;
			}
			for (String key : Set.of("pre", "post", "vector")) {
				if (fields.has(key) && !vectorSafe(fields.get(key), limit)) {
					return false;
				}
			}
			for (String key : Set.of("easingArg", "easingArgs")) {
				if (fields.has(key) && !numbers(fields.get(key), Double.MAX_VALUE)) {
					return false;
				}
			}
		}
		return true;
	}

	private static boolean vectorSafe(JsonElement value, double limit) {
		return numbers(value, limit) && (!value.isJsonArray() || value.getAsJsonArray().size() == 3);
	}

	private static boolean numbers(JsonElement value, double limit) {
		if (value.isJsonArray()) {
			JsonArray list = value.getAsJsonArray();
			for (JsonElement item : list) {
				if (!number(item, limit)) {
					return false;
				}
			}
			return list.size() <= 4;
		}
		return number(value, limit);
	}

	// A string here would be a Molang expression.
	private static boolean number(JsonElement value, double limit) {
		if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
			return false;
		}
		double number = value.getAsDouble();
		return Double.isFinite(number) && Math.abs(number) <= limit;
	}
}
