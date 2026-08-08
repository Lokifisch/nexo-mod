package dev.nexoclient.nexomod.auth;

import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import dev.nexoclient.nexomod.screen.SigningInScreen;

/** Starts a Microsoft sign-in and takes care of the screen transitions around it. */
public final class LoginFlow {
	private static final Logger LOGGER = LoggerFactory.getLogger("nexomod/auth");

	private LoginFlow() {}

	public static void start(Screen returnTo) {
		Minecraft mc = Minecraft.getInstance();
		AtomicBoolean cancelled = new AtomicBoolean(false);

		mc.setScreen(new SigningInScreen(returnTo, cancelled));
		MicrosoftAuth.login(cancelled)
				.whenComplete((account, error) -> mc.execute(() -> {
			if (error != null) {
				if (!cancelled.get()) {
					LOGGER.error("Microsoft sign-in failed", error);
					mc.setScreen(new net.minecraft.client.gui.screens.AlertScreen(
							() -> mc.setScreen(returnTo),
							Component.translatable("nexomod.login.failedTitle"),
							errorMessage(error)));
				} else {
					mc.setScreen(returnTo);
				}
				return;
			}
			AccountStore.get().upsertAndActivate(account);
			SessionSwap.activate(account);
			mc.setScreen(returnTo);
		}));
	}

	/**
	 * Refreshes (if needed) and switches to a saved account. The refresh is a
	 * chain of network round-trips, so callers should be showing a loading
	 * state whenever {@code account.isExpired()}; setting {@code cancelled}
	 * abandons the switch — nothing is applied and neither callback runs.
	 */
	public static void switchTo(MinecraftAccount account, AtomicBoolean cancelled, Runnable onDone, java.util.function.Consumer<Throwable> onError) {
		Minecraft mc = Minecraft.getInstance();
		java.util.concurrent.CompletableFuture.supplyAsync(() -> account.isExpired() ? MicrosoftAuth.refresh(account) : account)
				.whenComplete((refreshed, error) -> mc.execute(() -> {
					if (cancelled.get()) {
						return; // User backed out — don't yank whatever screen they're on now.
					}
					if (error != null) {
						onError.accept(error);
						return;
					}
					AccountStore.get().upsertAndActivate(refreshed);
					SessionSwap.activate(refreshed);
					onDone.run();
				}));
	}

	private static MutableComponent errorMessage(Throwable error) {
		Throwable cause = error.getCause() != null ? error.getCause() : error;
		return Component.literal(cause.getMessage() != null ? cause.getMessage() : cause.toString());
	}
}
