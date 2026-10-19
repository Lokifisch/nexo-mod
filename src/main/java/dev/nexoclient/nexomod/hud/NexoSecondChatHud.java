package dev.nexoclient.nexomod.hud;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.List;

import org.joml.Matrix3x2fStack;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;

import dev.nexoclient.nexomod.NexoMod;
import dev.nexoclient.nexomod.chat.NexoChatTweaks;

/**
 * The second chat panel: lines routed here by {@link NexoChatTweaks#route}.
 * Fades like vanilla chat (10 s) and stays fully visible while a chat screen is
 * open. Lines are word-wrapped once, when they arrive, so drawing allocates
 * nothing per frame.
 */
public final class NexoSecondChatHud implements HudElement {
	public static final NexoSecondChatHud INSTANCE = new NexoSecondChatHud();

	private static final int LINE_HEIGHT = 9;
	private static final int NOMINAL_WIDTH = 240;
	private static final int MAX_ENTRIES = 100;
	private static final long VISIBLE_MS = 10000;
	private static final long FADE_MS = 1000;
	private static final int EDGE_MARGIN = 4;

	private record Entry(List<FormattedCharSequence> lines, long addedAt) {
	}

	private final ArrayDeque<Entry> entries = new ArrayDeque<>();

	private NexoSecondChatHud() {
	}

	public static void register() {
		HudElementRegistry.attachElementAfter(VanillaHudElements.CHAT,
				Identifier.fromNamespaceAndPath(NexoMod.MOD_ID, "second_chat"), INSTANCE);
		// Lines from the previous server must not reappear on the next one.
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT
				.register((handler, client) -> INSTANCE.entries.clear());
	}

	public void add(Component text) {
		entries.addLast(new Entry(Minecraft.getInstance().font.split(text, NOMINAL_WIDTH), System.currentTimeMillis()));
		while (entries.size() > MAX_ENTRIES) {
			entries.pollFirst();
		}
	}

	public static ScreenRectangle resolveBounds(int guiWidth, int guiHeight) {
		NexoHudLayout.Position override = NexoHudLayout.get().get(NexoHudLayout.Element.SECOND_CHAT);
		float scale = override != null ? override.scale : 1f;
		int width = Math.round(NOMINAL_WIDTH * scale);
		int height = Math.round(NexoChatTweaks.secondLines * LINE_HEIGHT * scale);
		int x = override != null ? override.x : guiWidth - NOMINAL_WIDTH - EDGE_MARGIN;
		int y = override != null ? override.y : guiHeight - 40 - NexoChatTweaks.secondLines * LINE_HEIGHT;
		return NexoHudBounds.clamp(x, y, width, height, guiWidth, guiHeight);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker delta) {
		if (!NexoChatTweaks.secondEnabled || entries.isEmpty() || NexoHudVisibility.hidden()) {
			return;
		}
		Minecraft client = Minecraft.getInstance();
		if (client.player == null || client.gui.hud.isHidden()) {
			return;
		}
		boolean open = client.gui.screen() instanceof ChatScreen;
		long now = System.currentTimeMillis();
		int maxLines = NexoChatTweaks.secondLines;

		NexoHudLayout.Position override = NexoHudLayout.get().get(NexoHudLayout.Element.SECOND_CHAT);
		float scale = override != null ? override.scale : 1f;
		ScreenRectangle bounds = resolveBounds(graphics.guiWidth(), graphics.guiHeight());

		Matrix3x2fStack pose = graphics.pose();
		pose.pushMatrix();
		pose.scale(scale, scale);
		int left = Math.round(bounds.left() / scale);
		int bottom = Math.round(bounds.top() / scale) + maxLines * LINE_HEIGHT;

		int drawn = 0;
		Iterator<Entry> it = entries.descendingIterator();
		while (it.hasNext() && drawn < maxLines) {
			Entry entry = it.next();
			long age = now - entry.addedAt();
			int alpha = 255;
			if (!open) {
				if (age > VISIBLE_MS) {
					break; // older entries are older still
				}
				long intoFade = age - (VISIBLE_MS - FADE_MS);
				if (intoFade > 0) {
					alpha = (int) (255 - 255 * intoFade / FADE_MS);
				}
			}
			if (alpha < 8) {
				continue;
			}
			List<FormattedCharSequence> lines = entry.lines();
			for (int i = lines.size() - 1; i >= 0 && drawn < maxLines; i--) {
				drawn++;
				int y = bottom - drawn * LINE_HEIGHT;
				graphics.fill(left - 2, y, left + NOMINAL_WIDTH + 2, y + LINE_HEIGHT, (alpha * 100 / 255) << 24);
				graphics.text(client.font, lines.get(i), left, y + 1, (alpha << 24) | 0xFFFFFF);
			}
		}
		pose.popMatrix();
	}
}
