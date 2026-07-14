package dev.nexoclient.nexomod.auth;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/**
 * A signed-in Minecraft account: its game profile (name/uuid), the current
 * Minecraft session token, and the Microsoft refresh token needed to renew
 * that session later without asking the user to sign in again. Offline
 * accounts (no Microsoft sign-in) reuse this same record with
 * {@code offline = true} and no real tokens, so they can live in the same
 * store and switcher UI as real accounts.
 */
public record MinecraftAccount(
		String name,
		UUID uuid,
		String minecraftAccessToken,
		String microsoftRefreshToken,
		Instant expiresAt,
		boolean offline) {

	public boolean isExpired() {
		return !offline && Instant.now().isAfter(expiresAt);
	}

	public static MinecraftAccount offline(String username) {
		UUID uuid = UUID.nameUUIDFromBytes(("offline:" + username).getBytes(StandardCharsets.UTF_8));
		return new MinecraftAccount(username, uuid, "invalidtoken", "", Instant.MAX, true);
	}
}
