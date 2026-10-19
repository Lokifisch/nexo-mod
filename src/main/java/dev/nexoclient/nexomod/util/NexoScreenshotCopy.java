package dev.nexoclient.nexomod.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.network.chat.Component;

import dev.nexoclient.nexomod.screen.NexoConfig;

/**
 * Keybind that takes a vanilla screenshot (saved as usual) and also puts the
 * image on the system clipboard. Java has no usable image clipboard inside
 * Minecraft's headless AWT, so the clipboard is filled by a fixed external
 * tool; the PNG path travels via stdin / an environment variable, never in
 * command text.
 */
public final class NexoScreenshotCopy {
	private static KeyMapping key;

	private NexoScreenshotCopy() {
	}

	/** Unbound by default. */
	public static void register() {
		key = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.nexomod.screenshotCopy",
				InputConstants.Type.KEYBOARD, InputConstants.UNKNOWN.getValue(), KeyMapping.Category.MISC));
		ClientTickEvents.END_CLIENT_TICK.register(NexoScreenshotCopy::tick);
	}

	private static void tick(Minecraft client) {
		if (key == null) {
			return;
		}
		while (key.consumeClick()) {
			if (!NexoConfig.get().screenshotCopyEnabled() || client.level == null) {
				continue;
			}
			Screenshot.grab(client.gameDirectory, client.gameRenderer.mainRenderTarget(),
					message -> client.execute(() -> client.gui.hud.getChat().addClientSystemMessage(message)));
			Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), image -> {
				Path tmp = null;
				try {
					tmp = Files.createTempFile("nexo-shot", ".png");
					image.writeToFile(tmp);
				} catch (IOException e) {
					if (tmp != null) {
						try {
							Files.deleteIfExists(tmp);
						} catch (IOException ignored) {
							// best effort
						}
					}
					tmp = null;
					say(client, "Screenshot copy failed: " + e.getMessage());
				} finally {
					image.close();
				}
				if (tmp != null) {
					Path file = tmp;
					Thread t = new Thread(() -> copy(client, file), "nexo-screenshot-copy");
					t.setDaemon(true);
					t.start();
				}
			});
		}
	}

	private static void copy(Minecraft client, Path png) {
		try {
			String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
			ProcessBuilder pb;
			if (os.contains("win")) {
				// Absolute: Windows searches the working directory (the game
				// folder) before PATH, so a bare name could run a planted copy.
				String powershell = System.getenv().getOrDefault("SystemRoot", "C:\\Windows")
						+ "\\System32\\WindowsPowerShell\\v1.0\\powershell.exe";
				pb = new ProcessBuilder(powershell, "-NoProfile", "-STA", "-Command",
						"Add-Type -AssemblyName System.Windows.Forms,System.Drawing;"
								+ "[Windows.Forms.Clipboard]::SetImage([Drawing.Image]::FromFile($env:NEXO_IMG))");
			} else if (os.contains("mac")) {
				pb = new ProcessBuilder("osascript", "-e",
						"set the clipboard to (read (POSIX file (system attribute \"NEXO_IMG\")) as «class PNGf»)");
			} else if (System.getenv("WAYLAND_DISPLAY") != null) {
				pb = new ProcessBuilder(List.of("wl-copy", "--type", "image/png"));
				pb.redirectInput(png.toFile());
			} else {
				pb = new ProcessBuilder(List.of("xclip", "-selection", "clipboard", "-t", "image/png", "-i"));
				pb.redirectInput(png.toFile());
			}
			pb.environment().put("NEXO_IMG", png.toString());
			pb.redirectErrorStream(true);
			pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
			Process p = pb.start();
			// wl-copy/xclip fork a server that outlives the call; they return once
			// the clipboard is claimed. A hang means something is wrong.
			if (!p.waitFor(10, TimeUnit.SECONDS)) {
				p.destroyForcibly();
				say(client, "Screenshot copy failed: clipboard tool timed out");
			} else if (p.exitValue() != 0) {
				say(client, "Screenshot copy failed: clipboard tool exited " + p.exitValue());
			} else {
				say(client, "Screenshot copied");
			}
		} catch (IOException e) {
			say(client, "Screenshot copy failed: " + e.getMessage() + " (is wl-copy / xclip installed?)");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} finally {
			// ponytail: xclip serves the image from the file's bytes it already read; deleting is safe.
			try {
				Files.deleteIfExists(png);
			} catch (IOException ignored) {
			}
		}
	}

	private static void say(Minecraft client, String text) {
		client.execute(() -> client.gui.hud.getChat().addClientSystemMessage(Component.literal(text)));
	}
}
