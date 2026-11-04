package dev.nexoclient.nexomod.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import dev.nexoclient.nexomod.emotes.NexoEmotes;

/** Hold the key, move the mouse to a slot, release to play. Eight slots, filled by the player in the wardrobe. */
public class NexoEmoteWheelScreen extends Screen {
	private static final int SLOTS = 8;
	private static final int SLOT_SIZE = 62;
	private static final float DEAD_ZONE = 34F;

	private final NexoEmotes.Emote[] emotes = NexoEmotes.wheel();
	private final boolean empty = java.util.Arrays.stream(emotes).allMatch(java.util.Objects::isNull);
	private int hovered = -1;

	public NexoEmoteWheelScreen() {
		super(Component.translatable("nexomod.emote.wheel"));
	}

	@Override
	public boolean isInGameUi() {
		// Vanilla's transparent background: the emote plays on your character, which must stay sharp.
		return true;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	private int radius() {
		return Math.min(Math.min(width, height) / 2 - SLOT_SIZE / 2 - 8, 120);
	}

	private int updateHover(int mouseX, int mouseY) {
		float dx = mouseX - width / 2F;
		float dy = mouseY - height / 2F;
		if (dx * dx + dy * dy < DEAD_ZONE * DEAD_ZONE) {
			return -1;
		}
		// Slot 0 straight up, clockwise.
		float angle = (float) Math.toDegrees(Math.atan2(dx, -dy));
		int slot = Mth.positiveModulo(Math.round(angle / (360F / SLOTS)), SLOTS);
		return emotes[slot] != null ? slot : -1;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		hovered = updateHover(mouseX, mouseY);
		int cx = width / 2;
		int cy = height / 2;
		int radius = radius();
		graphics.fill(0, 0, width, height, 0x66000000);
		for (int i = 0; i < SLOTS; i++) {
			double angle = Math.toRadians(i * 360D / SLOTS - 90D);
			int x = cx + (int) (Math.cos(angle) * radius);
			int y = cy + (int) (Math.sin(angle) * radius);
			int x0 = x - SLOT_SIZE / 2;
			int y0 = y - SLOT_SIZE / 2;
			int x1 = x0 + SLOT_SIZE;
			int y1 = y0 + SLOT_SIZE;
			boolean hot = i == hovered;
			if (hot) {
				NexoShapes.glow(graphics, x0, y0, x1, y1, NexoStyle.ACCENT, 8, 0.3F);
			}
			NexoShapes.outlined(graphics, x0, y0, x1, y1, hot ? NexoStyle.mix(NexoStyle.RAISED, NexoStyle.ACCENT, 0.2F) : NexoStyle.RAISED,
					hot ? NexoStyle.ACCENT : NexoStyle.CONTROL, 8);
			if (emotes[i] != null) {
				NexoEmotes.Emote emote = emotes[i];
				NexoWardrobeScreen.drawPosed(graphics, emote.pose(), 5F, -24F, x - 18, y0 + 3, x + 18, y1 - 13);
				graphics.centeredText(font, font.substrByWidth(emote.name(), SLOT_SIZE - 6).getString(), x, y1 - 11, hot ? NexoStyle.TEXT_PRIMARY : NexoStyle.MUTED);
			}
		}
		Component label = hovered >= 0 ? emotes[hovered].name() : title;
		graphics.centeredText(font, label, cx, cy - 4, NexoStyle.TEXT_PRIMARY);
		if (empty) {
			graphics.centeredText(font, Component.translatable("nexomod.emote.wheel.empty"), cx, cy + 6, NexoStyle.MUTED);
		}
	}

	private void playHovered() {
		Minecraft mc = Minecraft.getInstance();
		mc.gui.setScreen(null);
		if (hovered >= 0) {
			NexoEmotes.play(emotes[hovered]);
		} else {
			// Opening the wheel and letting go in the middle: the way out of a loop.
			NexoEmotes.stop();
		}
	}

	@Override
	public boolean keyReleased(KeyEvent event) {
		if (NexoEmotes.wheelKey().matches(event)) {
			playHovered();
			return true;
		}
		return super.keyReleased(event);
	}

	/** A click plays too: the wheel key may be a mouse button, or already released. */
	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		playHovered();
		return true;
	}
}
