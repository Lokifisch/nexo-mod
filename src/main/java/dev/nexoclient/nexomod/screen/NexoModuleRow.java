package dev.nexoclient.nexomod.screen;

import java.util.function.BooleanSupplier;

import org.joml.Matrix3x2fStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * A module row: name + a one-line description + a sliding on/off pill,
 * colored by whatever {@code state} currently reports, click anywhere on it
 * to act — the "colored module list" pattern most other clients use for a
 * feature menu, in place of a vanilla button carrying an On/Off label.
 *
 * <p>One widget serves two jobs depending on the caller: a QoL-menu row uses
 * {@code state} to tint itself by whether that module is enabled and
 * {@code onPress} to open its config screen; a config screen's own enabled
 * toggle uses the same widget with {@code onPress} flipping the setting
 * directly. Either way the row never needs to know which job it's doing —
 * it just reflects {@code state} and reports a click.
 *
 * <p>Two things here animate off wall-clock time rather than ticks, so
 * they keep moving on a screen that doesn't pause the game and stay
 * framerate-independent:
 * <ul>
 *   <li>the pill knob and the hover halo ease toward their target by a
 *   per-second rate multiplied by the real frame delta, so a 30fps client
 *   and a 200fps one take the same wall time to settle;</li>
 *   <li>the whole row slides in and fades up once on first appearance,
 *   offset by {@link #stagger(int)} so a menu's worth of rows cascades
 *   instead of snapping in as one block.</li>
 * </ul>
 * All of it is draw-time only — the widget's real bounds never move, so a
 * click during the intro still lands on the row it looks like it's over.
 */
public class NexoModuleRow extends AbstractButton {
	private static final int CORNER_RADIUS = 4;
	private static final int TOGGLE_WIDTH = 20;
	private static final int TOGGLE_HEIGHT = 10;
	private static final int TOGGLE_MARGIN = 9;
	/**
	 * Slop around the pill's drawn rectangle that still counts as hitting it. The
	 * pill is 20×10, which is a small target to ask anyone to hit exactly; this
	 * makes the clickable area 32×22 without making the pill look chunky.
	 */
	private static final int TOGGLE_HIT_PADDING = 6;
	/** Description text is drawn at this scale, so a long line still clears the toggle pill. */
	private static final float DESCRIPTION_SCALE = 0.8F;
	private static final long REVEAL_MILLIS = 220L;
	private static final long STAGGER_MILLIS = 35L;
	/** How far left of its resting place a row starts its slide-in. */
	private static final int REVEAL_SLIDE = 16;

	private final Component description;
	private final BooleanSupplier state;
	private final Runnable onPress;
	private final long bornMillis = System.currentTimeMillis();

	/** Optional: what a click on the pill itself does, instead of {@link #onPress}. */
	private Runnable onToggle;

	private long revealDelayMillis;
	private boolean revealed;
	/** 1 normally; eased to 0 on a row that has been filtered out and is only still drawn to fade away. */
	private float fade = 1F;
	/** Eased 0..1 position of the pill knob. Negative until the first frame snaps it to {@code state}. */
	private float knob = -1F;
	private float hoverGlow;
	private long lastFrameMillis;

	public NexoModuleRow(int x, int y, int width, int height, Component name, Component description,
			BooleanSupplier state, Runnable onPress) {
		super(x, y, width, height, name);
		this.description = description;
		this.state = state;
		this.onPress = onPress;
	}

	/** Delays this row's slide-in by its position in the list, so a grid of them cascades. */
	public void setFade(float fade) {
		this.fade = fade;
	}

	/** Skips the entrance animation — for rows rebuilt while the menu is already open (search, resize). */
	public NexoModuleRow noReveal() {
		this.revealed = true;
		return this;
	}

	public NexoModuleRow stagger(int index) {
		this.revealDelayMillis = index * STAGGER_MILLIS;
		return this;
	}

	/**
	 * Makes the pill a control rather than a status light: clicking it runs
	 * {@code action} and leaves {@link #onPress} for clicks anywhere else on the
	 * row. Without this the whole row is one button — which is right for a config
	 * screen, where pressing the row already toggles the setting, and wrong for a
	 * menu row, where pressing it opens a sub-screen and there is no way to flip
	 * the module without going in and coming back out.
	 */
	public NexoModuleRow withToggle(Runnable action) {
		this.onToggle = action;
		return this;
	}

	@Override
	public void onPress(InputWithModifiers input) {
		onPress.run();
	}

	/**
	 * Splits the row into two click targets. Keyboard activation still goes to
	 * {@link #onPress}, since there is no cursor to be over the pill with — the
	 * pill shortcut is a mouse affordance, not the only way to reach the setting.
	 */
	@Override
	public void onClick(MouseButtonEvent event, boolean doubleClick) {
		if (onToggle != null && overToggle(event.x(), event.y())) {
			onToggle.run();
			return;
		}
		super.onClick(event, doubleClick);
	}

	/** Left edge of the pill at rest — the reveal slide is cosmetic and never moves the hit box. */
	private int toggleLeft() {
		return getX() + getWidth() - TOGGLE_MARGIN - TOGGLE_WIDTH;
	}

	private int toggleTop() {
		return getY() + (getHeight() - TOGGLE_HEIGHT) / 2;
	}

	private boolean overToggle(double mouseX, double mouseY) {
		return mouseX >= toggleLeft() - TOGGLE_HIT_PADDING
				&& mouseX <= toggleLeft() + TOGGLE_WIDTH + TOGGLE_HIT_PADDING
				&& mouseY >= toggleTop() - TOGGLE_HIT_PADDING
				&& mouseY <= toggleTop() + TOGGLE_HEIGHT + TOGGLE_HIT_PADDING;
	}

	@Override
	protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		long now = System.currentTimeMillis();
		float reveal = reveal(now) * fade;
		if (reveal <= 0F) {
			return;
		}

		boolean on = state.getAsBoolean();
		advanceAnimations(now, on);

		int slide = Math.round((1F - reveal) * -REVEAL_SLIDE);
		int x0 = getX() + slide;
		int y0 = getY();
		int x1 = x0 + getWidth();
		int y1 = y0 + getHeight();

		int accent = NexoStyle.ACCENT;

		// A flat card. Enabled is the one state that earns light: accent outline,
		// faint tint, soft glow. Hover on an off row only tints its outline, so a
		// hovered row never looks like an enabled one.
		if (NexoRowRenderer.isFlat(this)) {
			// A list row on the quick-settings screen: no card, the switch alone
			// carries the on/off state.
			NexoRowRenderer.flatBox(graphics, x0, y0, x1, y1, active, isHoveredOrFocused());
			drawToggle(graphics, slide, accent, reveal, onToggle != null && overToggle(mouseX, mouseY));
			drawLabels(graphics, x0, y0, on, reveal);
			return;
		}
		if (on) {
			NexoShapes.glow(graphics, x0, y0, x1, y1, accent, CORNER_RADIUS, 0.16F * reveal);
		}
		int fill = on ? NexoStyle.mix(NexoStyle.RAISED, accent, 0.18F) : NexoStyle.RAISED;
		// Hover/focus brightens an enabled row's outline toward white, so a keyboard
		// user tabbing through still sees where focus is on a lit row.
		int border = on
				? NexoStyle.mix(accent, 0xFFFFFFFF, 0.35F * hoverGlow)
				: NexoStyle.mix(NexoStyle.LINE, accent, 0.6F * hoverGlow);
		// The fill goes on top of the outline colour, so while the row fades in
		// (fill translucent) the outline would tint the whole card; fading the
		// outline by reveal² keeps that leak under a few percent.
		NexoShapes.outlined(graphics, x0, y0, x1, y1,
				NexoStyle.fade(fill, reveal), NexoStyle.fade(border, reveal * reveal), CORNER_RADIUS);

		boolean pillHovered = onToggle != null && overToggle(mouseX, mouseY);
		drawToggle(graphics, slide, accent, reveal, pillHovered);
		drawLabels(graphics, x0, y0, on, reveal);
	}

	/** The sliding on/off pill at the row's right edge. */
	private void drawToggle(GuiGraphicsExtractor graphics, int slide, int accent, float reveal, boolean pillHovered) {
		int tx0 = toggleLeft() + slide;
		int tx1 = tx0 + TOGGLE_WIDTH;
		int ty0 = toggleTop();
		int ty1 = ty0 + TOGGLE_HEIGHT;

		// A ring while the cursor is over the pill specifically — without it there
		// is nothing telling anyone that this part of the row does something else.
		if (pillHovered) {
			NexoShapes.fillRounded(graphics, tx0 - 2, ty0 - 2, tx1 + 2, ty1 + 2,
					NexoStyle.fade(NexoStyle.BORDER_BRIGHT, 0.35F * reveal), TOGGLE_HEIGHT / 2 + 2);
		}
		// On: tinted track in an accent outline, white knob. Off: dark track, dim
		// knob — so the two differ in lightness, not only in hue.
		int track = NexoStyle.mix(0xFF16161C, NexoStyle.mix(0xFF0A0A0D, accent, 0.3F), knob);
		int edge = NexoStyle.mix(0xFF2A2A34, accent, knob);
		// Edge with the fast fill: at 10px tall the smooth corners don't show,
		// and every row draws one of these each frame.
		NexoShapes.fillRoundedFast(graphics, tx0, ty0, tx1, ty1, NexoStyle.fade(edge, reveal * reveal), TOGGLE_HEIGHT / 2);
		NexoShapes.fillRounded(graphics, tx0 + 1, ty0 + 1, tx1 - 1, ty1 - 1, NexoStyle.fade(track, reveal), TOGGLE_HEIGHT / 2 - 1);

		int size = TOGGLE_HEIGHT - 4;
		int kx = tx0 + 2 + Math.round(knob * (TOGGLE_WIDTH - 4 - size));
		NexoShapes.fillRounded(graphics, kx, ty0 + 2, kx + size, ty1 - 2,
				NexoStyle.fade(NexoStyle.mix(0xFF5A5A68, 0xFFFFFFFF, knob), reveal), size / 2);
	}

	private void drawLabels(GuiGraphicsExtractor graphics, int x0, int y0, boolean on, float reveal) {
		Minecraft mc = Minecraft.getInstance();
		int textX = x0 + 9;
		int nameColor = on ? NexoStyle.TEXT_PRIMARY : NexoStyle.TEXT_SECONDARY;
		Component name = getMessage().copy().withStyle(style -> style.withColor(nameColor).withBold(true));
		graphics.text(mc.font, name, textX, y0 + 8, NexoStyle.fade(nameColor, reveal));

		Matrix3x2fStack pose = graphics.pose();
		// Clipped to the row: translated or longer descriptions otherwise run out of the box.
		int maxPx = Math.round((getX() + getWidth() - 8 - textX) / DESCRIPTION_SCALE);
		String line = description.getString();
		if (mc.font.width(line) > maxPx) {
			line = mc.font.plainSubstrByWidth(line, maxPx - mc.font.width("…")).stripTrailing() + "…";
		}
		pose.pushMatrix();
		pose.scale(DESCRIPTION_SCALE, DESCRIPTION_SCALE);
		graphics.text(mc.font, line,
				Math.round(textX / DESCRIPTION_SCALE), Math.round((y0 + 21) / DESCRIPTION_SCALE),
				NexoStyle.fade(NexoStyle.MUTED, reveal));
		pose.popMatrix();
	}

	/**
	 * Eases the knob and hover halo toward their targets at a fixed per-second
	 * rate, using the real elapsed time rather than a fixed per-frame step so
	 * the animation runs at the same speed whatever the framerate. The delta is
	 * capped so a stalled frame (a chunk build, an alt-tab) doesn't teleport them.
	 */
	private void advanceAnimations(long now, boolean on) {
		if (knob < 0F) {
			knob = on ? 1F : 0F;
		}
		float delta = lastFrameMillis == 0L ? 0F : Math.min(100L, now - lastFrameMillis) / 1000F;
		lastFrameMillis = now;
		knob = approach(knob, on ? 1F : 0F, delta * 9F);
		hoverGlow = approach(hoverGlow, isHoveredOrFocused() ? 1F : 0F, delta * 10F);
	}

	private static float approach(float current, float target, float step) {
		if (current < target) {
			return Math.min(target, current + step);
		}
		return Math.max(target, current - step);
	}

	/** 0 before this row's turn, 1 once it has fully arrived, ease-out cubic in between. */
	private float reveal(long now) {
		if (revealed) {
			return 1F;
		}
		long elapsed = now - bornMillis - revealDelayMillis;
		if (elapsed <= 0L) {
			return 0F;
		}
		if (elapsed >= REVEAL_MILLIS) {
			return 1F;
		}
		float remaining = 1F - elapsed / (float) REVEAL_MILLIS;
		return 1F - remaining * remaining * remaining;
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
		defaultButtonNarrationText(output);
	}
}
