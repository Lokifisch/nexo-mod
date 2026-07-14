package dev.nexoclient.nexomod.auth;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import com.mojang.authlib.exceptions.AuthenticationException;
import com.mojang.authlib.minecraft.UserApiService;
import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService;

import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.multiplayer.ProfileKeyPairManager;
import net.minecraft.util.Util;

import dev.nexoclient.nexomod.NexoMod;
import dev.nexoclient.nexomod.mixin.MinecraftUserAccessor;

/**
 * Makes the game actually use a saved/newly signed-in account. Rebuilds
 * every session-derived field Minecraft normally only sets once at launch
 * (see {@code Minecraft}'s constructor) — not just {@code user} — otherwise
 * things like the chat-signing profile key pair stay bound to whichever
 * account the game actually launched with and start failing with 401s
 * against the previous session's now-mismatched token.
 */
public final class SessionSwap {
	private SessionSwap() {}

	public static void activate(MinecraftAccount account) {
		LauncherAccount.captureIfNeeded();
		User user = new User(account.name(), account.uuid(), account.minecraftAccessToken(), Optional.empty(), Optional.empty());
		if (account.offline()) {
			applyOffline(user);
		} else {
			apply(user);
		}
	}

	/** Switches back to whatever account this game process was actually launched with. */
	public static void restoreLauncherAccount() {
		LauncherAccount.captureIfNeeded();
		LauncherAccount.user().ifPresent(SessionSwap::apply);
	}

	private static void apply(User user) {
		Minecraft mc = Minecraft.getInstance();
		MinecraftUserAccessor accessor = (MinecraftUserAccessor) (Object) mc;

		accessor.nexomod$setUser(user);

		accessor.nexomod$setProfileFuture(CompletableFuture.supplyAsync(
				() -> mc.services().sessionService().fetchProfile(user.getProfileId(), true), Util.nonCriticalIoPool()));

		// A fresh service is equivalent to the one built at launch (it's a stateless
		// HTTP client factory keyed only by the access token passed to each call) —
		// no need to capture the original instance via a constructor-wrapping mixin.
		YggdrasilAuthenticationService authService = new YggdrasilAuthenticationService(mc.getProxy());
		UserApiService userApiService = authService.createUserApiService(user.getAccessToken());
		accessor.nexomod$setUserApiService(userApiService);

		accessor.nexomod$setUserPropertiesFuture(CompletableFuture.supplyAsync(() -> {
			try {
				return userApiService.fetchProperties();
			} catch (AuthenticationException e) {
				NexoMod.LOGGER.error("Failed to fetch user properties after account switch", e);
				return UserApiService.OFFLINE_PROPERTIES;
			}
		}, Util.nonCriticalIoPool()));

		accessor.nexomod$setProfileKeyPairManager(
				ProfileKeyPairManager.create(userApiService, user, mc.gameDirectory.toPath()));

		NexoMod.LOGGER.info("Minecraft session for {} ({}) has been applied", user.getName(), user.getProfileId());
	}

	/** Mirrors Minecraft's own constructor branching for offline-developer-mode — no network calls for an invalid token. */
	private static void applyOffline(User user) {
		MinecraftUserAccessor accessor = (MinecraftUserAccessor) (Object) Minecraft.getInstance();

		accessor.nexomod$setUser(user);
		accessor.nexomod$setProfileFuture(CompletableFuture.completedFuture(null));
		accessor.nexomod$setUserApiService(UserApiService.OFFLINE);
		accessor.nexomod$setUserPropertiesFuture(CompletableFuture.completedFuture(UserApiService.OFFLINE_PROPERTIES));
		accessor.nexomod$setProfileKeyPairManager(ProfileKeyPairManager.EMPTY_KEY_MANAGER);

		NexoMod.LOGGER.info("Offline session for {} has been applied", user.getName());
	}
}
