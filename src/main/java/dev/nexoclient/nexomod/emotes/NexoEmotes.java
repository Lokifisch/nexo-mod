package dev.nexoclient.nexomod.emotes;

import java.util.List;
import java.util.UUID;

import com.mojang.blaze3d.platform.InputConstants;
import com.zigythebird.playeranim.animation.PlayerAnimResources;
import com.zigythebird.playeranim.animation.PlayerAnimationController;
import com.zigythebird.playeranim.api.PlayerAnimationAccess;
import com.zigythebird.playeranim.api.PlayerAnimationFactory;
import com.zigythebird.playeranimcore.animation.Animation;
import com.zigythebird.playeranimcore.enums.PlayState;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Avatar;

import dev.nexoclient.nexomod.screen.NexoEmoteWheelScreen;

/**
 * Emote playback on player models, through Player Animation Library. The player
 * who plays an emote stops it by sneaking, attacking or being hit, and whole-body
 * ones also by moving; the {@link Listener} is how the relay hears about both.
 */
public final class NexoEmotes {
	private static final Identifier LAYER = Identifier.fromNamespaceAndPath("nexomod", "emotes");

	/**
	 * A fixed pose for thumbnails: rotations in radians, since a model shown in a
	 * menu is not a player the library can animate.
	 */
	public record Pose(float rightArmX, float rightArmZ, float leftArmX, float leftArmZ) {
		public static final Pose NEUTRAL = new Pose(0F, 0F, 0F, 0F);
	}

	/**
	 * An emote: {@code id} is the stable name other players' clients look it up by.
	 * {@code walkable} ones leave the legs and body alone, so they keep playing while walking.
	 */
	public record Emote(String id, Component name, Pose pose, boolean walkable) {
		public Animation animation() {
			return id.length() == 64 ? NexoEmoteAccount.animation(id) : PlayerAnimResources.getAnimation(Identifier.fromNamespaceAndPath("nexomod", id));
		}
	}

	public interface Listener {
		void played(Emote emote);

		void stopped();
	}

	public static final List<Emote> STARTERS = List.of(
			starter("wave", new Pose(-2.97F, 0.26F, 0F, 0F), true),
			starter("cheer", new Pose(-2.97F, 0.2F, -2.97F, -0.2F), true),
			starter("point", new Pose(-1.57F, 0F, 0F, 0F), true),
			starter("clap", new Pose(-1.3F, 0F, -1.3F, 0F), true),
			starter("dance", new Pose(-2.2F, 0.2F, -1.05F, -0.5F), false),
			starter("bow", new Pose(-0.44F, 0F, -0.44F, 0F), false),
			starter("facepalm", new Pose(-2.35F, 0F, 0F, 0F), true),
			starter("tpose", new Pose(0F, 1.57F, 0F, -1.57F), true));

	private static KeyMapping wheelKey;
	private static Emote playing;
	private static Listener listener;
	private static int graceTicks;
	private static boolean sneakWasDown;

	private NexoEmotes() {
	}

	private static Emote starter(String id, Pose pose, boolean walkable) {
		return new Emote(id, Component.translatable("nexomod.emote." + id), pose, walkable);
	}

	public static void setListener(Listener value) {
		listener = value;
	}

	public static KeyMapping wheelKey() {
		return wheelKey;
	}

	public static void register() {
		// The library wants its layer registered during client init.
		PlayerAnimationFactory.ANIMATION_DATA_FACTORY.registerFactory(LAYER, 1000,
				player -> new PlayerAnimationController(player, (controller, state, animSetter) -> PlayState.STOP));
		wheelKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.nexomod.emoteWheel",
				InputConstants.Type.KEYBOARD, InputConstants.KEY_B, KeyMapping.Category.MISC));
		ClientTickEvents.END_CLIENT_TICK.register(NexoEmotes::tick);
		NexoEmoteRelay.register();
	}

	private static void tick(Minecraft mc) {
		NexoEmoteAccount.tick(mc);
		NexoEmoteRelay.tick(mc);
		if (mc.player == null) {
			playing = null;
			return;
		}
		while (mc.gui.screen() == null && wheelKey.consumeClick()) {
			mc.gui.setScreen(new NexoEmoteWheelScreen());
		}
		if (playing == null) {
			return;
		}
		// A moment's grace: the key press that opened the wheel may still be settling.
		if (graceTicks > 0) {
			graceTicks--;
		} else if (wantsToStop(mc)) {
			stop();
			return;
		}
		PlayerAnimationController controller = controller(mc.player);
		if (controller != null && !controller.isActive()) {
			playing = null;
			if (listener != null) {
				listener.stopped();
			}
		}
	}

	private static boolean wantsToStop(Minecraft mc) {
		var options = mc.options;
		// Pressing it, not holding it: toggle-crouch keeps this down all the time.
		boolean sneakDown = options.keyShift.isDown();
		boolean sneakPressed = sneakDown && !sneakWasDown;
		sneakWasDown = sneakDown;
		// Always: these also end a looping emote that walking would not.
		if (options.keyAttack.isDown() || sneakPressed || mc.player.hurtTime > 0) {
			return true;
		}
		return !playing.walkable() && (options.keyUp.isDown() || options.keyDown.isDown() || options.keyLeft.isDown()
				|| options.keyRight.isDown() || options.keyJump.isDown());
	}

	private static PlayerAnimationController controller(Avatar avatar) {
		return PlayerAnimationAccess.getPlayerAnimationLayer(avatar, LAYER) instanceof PlayerAnimationController controller ? controller : null;
	}

	/** Plays on the local player and tells the listener. */
	public static boolean play(Emote emote) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || !show(mc.player, emote.animation(), 0F)) {
			return false;
		}
		playing = emote;
		graceTicks = 5;
		sneakWasDown = mc.options.keyShift.isDown();
		if (listener != null) {
			listener.played(emote);
		}
		return true;
	}

	public static void stop() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) {
			hide(mc.player);
		}
		if (playing != null) {
			playing = null;
			if (listener != null) {
				listener.stopped();
			}
		}
	}

	/** What this player can play. Starters for now; the shop adds to it. */
	public static List<Emote> owned() {
		List<Emote> bought = NexoEmoteAccount.ownedEmotes();
		if (bought.isEmpty()) {
			return STARTERS;
		}
		// Bought ones first: these were chosen.
		List<Emote> all = new java.util.ArrayList<>(bought);
		all.addAll(STARTERS);
		return all;
	}

	/**
	 * The wheel's eight slots as the player arranged them in the wardrobe; null where a
	 * slot is empty or holds an emote this account no longer owns.
	 */
	public static Emote[] wheel() {
		String[] ids = dev.nexoclient.nexomod.screen.NexoConfig.get().emoteWheel();
		List<Emote> owned = owned();
		Emote[] slots = new Emote[ids.length];
		for (int i = 0; i < ids.length; i++) {
			String id = ids[i];
			slots[i] = owned.stream().filter(emote -> emote.id().equals(id)).findFirst().orElse(null);
		}
		return slots;
	}

	private static java.util.Map<String, NexoEmotePreview> starterPreviews;

	/** The emote's animation, read for posing a menu model; null while a bought one is still downloading. */
	public static NexoEmotePreview preview(Emote emote) {
		if (emote.id().length() == 64) {
			return NexoEmoteAccount.preview(emote.id());
		}
		if (starterPreviews == null) {
			starterPreviews = new java.util.HashMap<>();
			try (var in = NexoEmotes.class.getResourceAsStream("/assets/nexomod/player_animations/starter.json")) {
				byte[] data = in.readAllBytes();
				for (Emote starter : STARTERS) {
					starterPreviews.put(starter.id(), NexoEmotePreview.parse(data, starter.id()));
				}
			} catch (Exception e) {
				// The fixed thumbnail poses still work without it.
				dev.nexoclient.nexomod.NexoMod.LOGGER.warn("emote previews: could not read the starters", e);
			}
		}
		return starterPreviews.get(emote.id());
	}

	public static Emote playing() {
		return playing;
	}

	/** Shows an animation on any player's model, {@code fromTick} ticks in. */
	public static boolean show(Avatar avatar, Animation animation, float fromTick) {
		PlayerAnimationController controller = controller(avatar);
		if (controller == null || animation == null) {
			return false;
		}
		controller.triggerAnimation(animation, fromTick);
		return true;
	}

	public static boolean isShowing(Avatar avatar) {
		PlayerAnimationController controller = controller(avatar);
		return controller != null && controller.isActive();
	}

	public static void hide(Avatar avatar) {
		PlayerAnimationController controller = controller(avatar);
		if (controller != null) {
			controller.stopTriggeredAnimation();
		}
	}

	/** Used by the relay: a loaded remote player by account id, or null. */
	public static Avatar loadedPlayer(UUID id) {
		Minecraft mc = Minecraft.getInstance();
		return mc.level == null ? null : mc.level.getPlayerByUUID(id);
	}
}
