package dev.nexoclient.nexomod.auth;

import java.util.Optional;
import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.client.User;

/**
 * The account this game process was actually launched with — captured
 * once, the first time anything in the switcher touches the active
 * session, before {@link SessionSwap} ever has a chance to overwrite it.
 * That identity (and its session) is baked into the running process
 * (whatever started it put it there via launch args); the switcher can
 * change what the game *uses* going forward and can switch back to this
 * one, but there's no logging out of it from here — that's not a session
 * the switcher created, so it's not the switcher's to end.
 */
public final class LauncherAccount {
	private static volatile User originalUser;

	private LauncherAccount() {}

	/** Idempotent — only the first call after game start actually captures anything. */
	public static synchronized void captureIfNeeded() {
		if (originalUser != null) {
			return;
		}
		originalUser = Minecraft.getInstance().getUser();
	}

	public static boolean is(UUID accountUuid) {
		return originalUser != null && originalUser.getProfileId().equals(accountUuid);
	}

	public static Optional<User> user() {
		return Optional.ofNullable(originalUser);
	}
}
