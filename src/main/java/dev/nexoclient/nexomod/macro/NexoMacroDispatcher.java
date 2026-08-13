package dev.nexoclient.nexomod.macro;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.player.LocalPlayer;

/**
 * Polls every macro's key state once per client tick and fires it on the
 * press edge (or on an interval, for {@code REPEAT} macros). There's no
 * vanilla {@code KeyMapping} involved — macro keys are picked freely by the
 * user at runtime, so raw {@link InputConstants#isKeyDown} polling is used
 * instead of registering fixed keybinds.
 *
 * Macros never fire while any screen is open (chat, inventory, pause menu,
 * Nexo Settings, ...), which sidesteps the whole question of conflicting
 * with text input or vanilla keybinds — simpler than the original mod's
 * per-key conflict-strategy system this is inspired by, at the cost of not
 * supporting macros bound to keys you'd want to trigger from inside a GUI.
 */
public final class NexoMacroDispatcher {
	private static final Random RNG = new Random();

	private static final Map<String, Boolean> wasDown = new HashMap<>();
	private static final Map<String, Integer> repeatTicks = new HashMap<>();
	private static final Map<String, Deque<String>> pendingSends = new HashMap<>();
	private static final Map<String, Integer> pendingDelay = new HashMap<>();

	private NexoMacroDispatcher() {}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(NexoMacroDispatcher::tick);
	}

	private static void tick(Minecraft mc) {
		if (mc.player == null || mc.getConnection() == null) {
			return;
		}

		processPendingSends(mc);

		if (mc.screen != null) {
			return;
		}

		Window window = mc.getWindow();
		for (NexoMacro macro : NexoMacroConfig.get().macros()) {
			if (!macro.enabled || macro.keyCode < 0 || macro.commands.isEmpty()) {
				continue;
			}

			boolean down = InputConstants.isKeyDown(window, macro.keyCode) && modifierDown(window, macro.modifier);
			boolean prevDown = wasDown.getOrDefault(macro.id, false);
			wasDown.put(macro.id, down);

			if (macro.mode == NexoMacro.Mode.REPEAT) {
				if (down) {
					if (!prevDown) {
						repeatTicks.put(macro.id, 0);
						fire(mc, macro);
					} else {
						int ticks = repeatTicks.merge(macro.id, 1, Integer::sum);
						if (ticks >= Math.max(1, macro.repeatIntervalTicks)) {
							repeatTicks.put(macro.id, 0);
							fire(mc, macro);
						}
					}
				}
				continue;
			}

			if (down && !prevDown) {
				fire(mc, macro);
			}
		}
	}

	private static boolean modifierDown(Window window, NexoMacro.Modifier modifier) {
		return switch (modifier) {
			case NONE -> true;
			case SHIFT -> InputConstants.isKeyDown(window, InputConstants.KEY_LSHIFT) || InputConstants.isKeyDown(window, InputConstants.KEY_RSHIFT);
			case CTRL -> InputConstants.isKeyDown(window, InputConstants.KEY_LCONTROL) || InputConstants.isKeyDown(window, InputConstants.KEY_RCONTROL);
			case ALT -> InputConstants.isKeyDown(window, InputConstants.KEY_LALT) || InputConstants.isKeyDown(window, InputConstants.KEY_RALT);
		};
	}

	/**
	 * Runs a macro's actions once, without a key press.
	 *
	 * <h2>Why this exists and why it is safe to have in the light jar</h2>
	 *
	 * <p>The full jar's state-triggered automation
	 * ({@code dev.nexoclient.nexomod.full.macro}) needs to run a macro the
	 * player configured, and the code that knows how to run one is here. What
	 * makes a macro light-jar material is its <em>trigger</em> — a key the player
	 * pressed — so the trigger side is what lives in {@code src/full}, and this
	 * method is only the "send these lines" half. Nothing in {@code src/main}
	 * calls it, and it cannot fire on its own: something has to decide when.
	 *
	 * <p>It sends chat and commands, exactly as a key press would. It does not
	 * synthesise key or mouse input, so it cannot click, mine, or attack.
	 */
	public static void runNow(Minecraft mc, NexoMacro macro) {
		if (macro == null || !macro.enabled || macro.commands.isEmpty() || mc.player == null) {
			return;
		}
		fire(mc, macro);
	}

	private static void fire(Minecraft mc, NexoMacro macro) {
		switch (macro.mode) {
			case SEND, REPEAT -> queueSend(mc, macro);
			case CYCLE -> {
				int index = cycleIndex.merge(macro.id, 1, Integer::sum) - 1;
				sendOne(mc, macro.commands.get(Math.floorMod(index, macro.commands.size())));
			}
			case RANDOM -> sendOne(mc, macro.commands.get(RNG.nextInt(macro.commands.size())));
			case TYPE -> mc.setScreen(new ChatScreen(resolvePlaceholders(mc, macro.commands.get(0)), true));
		}
	}

	private static final Map<String, Integer> cycleIndex = new HashMap<>();

	private static void queueSend(Minecraft mc, NexoMacro macro) {
		Deque<String> queue = new ArrayDeque<>(macro.commands);
		String first = queue.poll();
		if (first != null) {
			sendOne(mc, first);
		}
		if (!queue.isEmpty()) {
			pendingSends.put(macro.id, queue);
			pendingDelay.put(macro.id, Math.max(1, macro.delayTicks));
		}
	}

	private static void processPendingSends(Minecraft mc) {
		if (pendingSends.isEmpty()) {
			return;
		}
		for (Map.Entry<String, Deque<String>> entry : Map.copyOf(pendingSends).entrySet()) {
			String id = entry.getKey();
			int ticksLeft = pendingDelay.merge(id, -1, Integer::sum);
			if (ticksLeft > 0) {
				continue;
			}
			Deque<String> queue = entry.getValue();
			String next = queue.poll();
			if (next != null) {
				sendOne(mc, next);
			}
			if (queue.isEmpty()) {
				pendingSends.remove(id);
				pendingDelay.remove(id);
			} else {
				pendingDelay.put(id, Math.max(1, findMacroDelay(id)));
			}
		}
	}

	private static int findMacroDelay(String macroId) {
		for (NexoMacro macro : NexoMacroConfig.get().macros()) {
			if (macro.id.equals(macroId)) {
				return macro.delayTicks;
			}
		}
		return 1;
	}

	private static void sendOne(Minecraft mc, String rawCommand) {
		String command = resolvePlaceholders(mc, rawCommand);
		if (command.isEmpty() || mc.player == null || mc.getConnection() == null) {
			return;
		}
		if (command.startsWith("/")) {
			mc.player.connection.sendCommand(command.substring(1));
		} else {
			mc.player.connection.sendChat(command);
		}
	}

	private static String resolvePlaceholders(Minecraft mc, String command) {
		String result = command;
		if (mc.getUser() != null) {
			result = result.replace("%myname%", mc.getUser().getName());
		}
		LocalPlayer player = mc.player;
		if (player != null) {
			result = result.replace("%pos%", (int) Math.floor(player.getX()) + " " + (int) Math.floor(player.getY()) + " " + (int) Math.floor(player.getZ()));
			result = result.replace("%x%", String.valueOf((int) Math.floor(player.getX())));
			result = result.replace("%y%", String.valueOf((int) Math.floor(player.getY())));
			result = result.replace("%z%", String.valueOf((int) Math.floor(player.getZ())));
		}
		if (result.contains("%clipboard%")) {
			String clipboard = mc.keyboardHandler.getClipboard();
			result = result.replace("%clipboard%", clipboard != null ? clipboard : "");
		}
		return result;
	}
}
