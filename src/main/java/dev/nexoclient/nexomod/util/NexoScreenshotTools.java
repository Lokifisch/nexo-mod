package dev.nexoclient.nexomod.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Locale;
import java.util.stream.Stream;

import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Two unbound-by-default keys: open the screenshots folder in the system file
 * manager, and delete the newest screenshot. The folder is opened by a fixed
 * per-OS opener with the path as a plain argument, never through a shell.
 * Uploading is deliberately absent: it would mean picking a host to send the
 * player's screenshots to.
 */
public final class NexoScreenshotTools {
	private static KeyMapping openKey;
	private static KeyMapping deleteKey;

	private NexoScreenshotTools() {
	}

	public static void register() {
		openKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.nexomod.openScreenshots",
				InputConstants.Type.KEYBOARD, InputConstants.UNKNOWN.getValue(), KeyMapping.Category.MISC));
		deleteKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.nexomod.deleteScreenshot",
				InputConstants.Type.KEYBOARD, InputConstants.UNKNOWN.getValue(), KeyMapping.Category.MISC));
		ClientTickEvents.END_CLIENT_TICK.register(NexoScreenshotTools::tick);
	}

	private static void tick(Minecraft client) {
		while (openKey.consumeClick()) {
			open(client, client.gameDirectory.toPath().toAbsolutePath().normalize().resolve("screenshots"));
		}
		while (deleteKey.consumeClick()) {
			deleteNewest(client, client.gameDirectory.toPath().toAbsolutePath().normalize().resolve("screenshots"));
		}
	}

	private static void open(Minecraft client, Path dir) {
		try {
			Files.createDirectories(dir);
			String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
			String opener = os.contains("win") ? "explorer" : os.contains("mac") ? "open" : "xdg-open";
			new ProcessBuilder(opener, dir.toString()).redirectErrorStream(true)
					.redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
		} catch (IOException e) {
			say(client, "Could not open screenshots folder: " + e.getMessage());
		}
	}

	private static void deleteNewest(Minecraft client, Path dir) {
		if (!Files.isDirectory(dir)) {
			say(client, "No screenshots to delete");
			return;
		}
		try (Stream<Path> files = Files.list(dir)) {
			Path newest = files.filter(p -> Files.isRegularFile(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)
							&& p.getFileName().toString().matches("\\d{4}-\\d{2}-\\d{2}_\\d{2}\\.\\d{2}\\.\\d{2}.*\\.png"))
					.max(Comparator.comparingLong(p -> p.toFile().lastModified())).orElse(null);
			if (newest == null) {
				say(client, "No screenshots to delete");
				return;
			}
			Files.delete(newest);
			say(client, "Deleted " + newest.getFileName());
		} catch (IOException e) {
			say(client, "Could not delete screenshot: " + e.getMessage());
		}
	}

	private static void say(Minecraft client, String text) {
		client.gui.hud.getChat().addClientSystemMessage(Component.literal(text));
	}
}
