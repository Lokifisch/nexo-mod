package dev.nexoclient.nexomod.macro;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** One keybind-triggered macro: a key (+ optional modifier) mapped to one or more chat commands/messages. */
public final class NexoMacro {
	public enum Modifier {
		NONE, SHIFT, CTRL, ALT
	}

	public enum Mode {
		/** Sends every command, in order (with an optional delay between each). */
		SEND,
		/** Sends the next command in the list each press, wrapping around. */
		CYCLE,
		/** Sends one random command from the list each press. */
		RANDOM,
		/** Like SEND, but keeps repeating on an interval for as long as the key is held. */
		REPEAT,
		/** Types the first command into the chat box without sending it. */
		TYPE
	}

	public String id = UUID.randomUUID().toString();
	public String name = "New macro";
	public int keyCode = -1;
	public Modifier modifier = Modifier.NONE;
	public Mode mode = Mode.SEND;
	public List<String> commands = new ArrayList<>();
	public int delayTicks = 0;
	public int repeatIntervalTicks = 10;
	public boolean enabled = true;
}
