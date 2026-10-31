import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.nexoclient.nexomod.emotes.NexoEmotePreview;

/** The shop's menu preview against the starter animations and the other file shapes. */
public class Check {
	static void near(float actual, float expected, String what) {
		if (Math.abs(actual - expected) > 0.01F) {
			throw new AssertionError(what + ": " + actual + " != " + expected);
		}
		System.out.println("ok: " + what);
	}

	static NexoEmotePreview starter(JsonObject all, String name) {
		return NexoEmotePreview.parse(("{\"animations\":{\"a\":" + all.get(name) + "}}").getBytes());
	}

	public static void main(String[] args) throws Exception {
		JsonObject all = JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonObject().getAsJsonObject("animations");
		NexoEmotePreview wave = starter(all, "wave");
		near(wave.rotation("right_arm", 0.25F)[0], -165F, "keyframe value");
		near(wave.rotation("right_arm", 0.375F)[2], 23.5F, "halfway between keyframes");
		near(wave.rotation("left_arm", 0.25F)[0], 0F, "a bone the file doesn't name stays still");
		near(wave.position(0.5F), 0.5F, "inside the animation");
		near(wave.position(1.8F), 1.6F, "rests at the end when it doesn't loop");
		near(wave.position(2.3F), 0.1F, "then plays again");
		near(wave.thumbnail()[0], -165F * (float) (Math.PI / 180D), "a card shows the raised arm");
		NexoEmotePreview tpose = starter(all, "tpose");
		near(tpose.position(2.5F), 0.5F, "a looping one has no rest");
		near(tpose.rotation("left_arm", 1F)[2], -90F, "left arm goes the other way");
		NexoEmotePreview shapes = NexoEmotePreview.parse(("{\"animations\":{\"a\":{\"animation_length\":1,\"bones\":{"
				+ "\"rightArm\":{\"rotation\":{\"0\":{\"post\":[10,0,0]},\"1\":{\"vector\":[20,0,0]}}},"
				+ "\"torso\":{\"rotation\":[0,45,0]},\"head\":{\"position\":[0,1,0]}}}}}").getBytes());
		near(shapes.rotation("right_arm", 0.5F)[0], 15F, "camelCase bone and frame objects");
		near(shapes.rotation("body", 0.5F)[1], 45F, "torso is the body, and a constant has no frames");
		near(shapes.rotation("head", 0.5F)[0], 0F, "only rotation is read");
	}
}
