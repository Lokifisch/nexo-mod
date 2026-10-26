package dev.nexoclient.nexomod.screen;

import java.util.function.BooleanSupplier;

import org.joml.Matrix3x2fStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * One module in the QoL menu's card grid (design/STYLE.md, the "In-game mod
 * menu" mockup): name at the top, the module's item icon in the middle, one
 * line of description, and along the bottom a settings button plus a
 * full-width ENABLED / DISABLED bar.
 *
 * <p>Two click targets: the bottom strip flips the module, anything else on
 * the card opens its settings. A module with nothing to configure has no
 * settings button and the whole card toggles it — a gear that only toggled
 * would be a gear that lies. Keyboard activation follows the same rule.
 *
 * <p>Like {@link NexoModuleRow}, the card fades in once on first appearance,
 * staggered by {@link #stagger(int)} so a grid cascades, and can be faded out
 * by its screen when a search drops it. All draw-time only — the real bounds
 * never move, so a click during the intro lands where it looks like it does.
 */
public class NexoModuleCard extends AbstractButton {
	private static final int RADIUS = 4;
	private static final int BAR_HEIGHT = 14;
	private static final int INSET = 6;
	private static final float SMALL = 0.75F;
	/** The item icon is drawn at 16px and scaled up by this. */
	private static final float ICON_SCALE = 1.5F;
	private static final long REVEAL_MILLIS = 220L;
	private static final long STAGGER_MILLIS = 25L;

	private final Component description;
	private final BooleanSupplier state;
	private final Runnable onToggle;
	/** Opens the module's settings; null when it has none. */
	private final Runnable onConfigure;
	/** Small edition tag in the top-right corner, e.g. "TACTICAL"; null for none. */
	private final Component tag;
	private final ItemStack icon;
	private final long bornMillis = System.currentTimeMillis();

	private long revealDelayMillis;
	private boolean revealed;
	private float fade = 1F;
	/** The scroll viewport's vertical span; a card wholly outside it is not drawn at all. */
	private int clipTop = Integer.MIN_VALUE;
	private int clipBottom = Integer.MAX_VALUE;

	public NexoModuleCard(int width, int height, Component name, Component description, BooleanSupplier state,
			Runnable onToggle, Runnable onConfigure, Component tag) {
		super(0, 0, width, height, name);
		this.description = description;
		this.state = state;
		this.onToggle = onToggle;
		this.onConfigure = onConfigure;
		this.tag = tag;
		this.icon = NexoModuleIcons.forName(name);
	}

	/** Set by the screen while a card it just filtered out fades away. */
	public void setFade(float fade) {
		this.fade = fade;
	}

	/**
	 * The scroll area's visible span. The vanilla scroll container scissors its
	 * children but still asks every one of them to draw, and a card is a few
	 * hundred fills — so the ones scrolled out of view bow out here.
	 */
	public void clip(int top, int bottom) {
		this.clipTop = top;
		this.clipBottom = bottom;
	}

	/** Skips the entrance — for cards rebuilt while the menu is already open (search, resize). */
	public NexoModuleCard noReveal() {
		this.revealed = true;
		return this;
	}

	public NexoModuleCard stagger(int index) {
		this.revealDelayMillis = index * STAGGER_MILLIS;
		return this;
	}

	@Override
	public void onPress(InputWithModifiers input) {
		(onConfigure != null ? onConfigure : onToggle).run();
	}

	@Override
	public void onClick(MouseButtonEvent event, boolean doubleClick) {
		if (overBar(event.x(), event.y())) {
			onToggle.run();
			return;
		}
		super.onClick(event, doubleClick);
	}

	private int barTop() {
		return getY() + getHeight() - INSET - BAR_HEIGHT;
	}

	/** The bar starts right of the settings button, or at the left inset when there is none. */
	private int barLeft() {
		return getX() + INSET + (onConfigure != null ? BAR_HEIGHT + 4 : 0);
	}

	/**
	 * The bar's hit box is the whole bottom strip right of the settings button,
	 * down to the card's edge — a slightly low or wide click on the bar should
	 * toggle, not leave the menu for the settings screen.
	 */
	private boolean overBar(double mx, double my) {
		int left = onConfigure != null ? barLeft() - 2 : getX();
		return mx >= left && mx < getX() + getWidth() && my >= barTop() - 2 && my < getY() + getHeight();
	}

	private boolean overGear(double mx, double my) {
		int gx = getX() + INSET;
		return onConfigure != null && mx >= gx && mx < gx + BAR_HEIGHT && my >= barTop() && my < barTop() + BAR_HEIGHT;
	}

	@Override
	protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		int x0 = getX();
		int y0 = getY();
		int x1 = x0 + getWidth();
		int y1 = y0 + getHeight();
		if (y1 < clipTop || y0 > clipBottom) {
			return;
		}
		float a = reveal(System.currentTimeMillis()) * fade;
		if (a <= 0F) {
			return;
		}
		boolean on = state.getAsBoolean();
		boolean hovered = isHoveredOrFocused();
		int accent = NexoStyle.ACCENT;

		// The card. Enabled is the state that earns light; hover brightens the
		// outline either way, so focus stays visible on a lit card too.
		if (on) {
			NexoShapes.glow(graphics, x0, y0, x1, y1, accent, RADIUS, 0.16F * a);
		}
		int fill = on ? NexoStyle.mix(NexoStyle.RAISED, accent, 0.10F) : NexoStyle.RAISED;
		int border = on
				? NexoStyle.mix(accent, 0xFFFFFFFF, hovered ? 0.35F : 0F)
				: (hovered ? NexoStyle.mix(NexoStyle.LINE, accent, 0.6F) : NexoStyle.LINE);
		// Fill over outline: fading the outline by a² keeps it from tinting the
		// translucent fill while the card fades in.
		NexoShapes.outlined(graphics, x0, y0, x1, y1, NexoStyle.fade(fill, a), NexoStyle.fade(border, a * a), RADIUS);

		Font font = Minecraft.getInstance().font;
		drawTexts(graphics, font, x0, y0, x1, on, a);
		drawIcon(graphics, x0, y0, a);
		drawGear(graphics, mouseX, mouseY, a);
		drawBar(graphics, font, mouseX, mouseY, x1, on, a);
	}

	/**
	 * The module's item, centred between the name and the description. Items
	 * can't be faded, so a card mid-fade simply doesn't show its icon until it
	 * is mostly there — better than an opaque item on a see-through card.
	 */
	private void drawIcon(GuiGraphicsExtractor graphics, int x0, int y0, float a) {
		if (a < 0.6F) {
			return;
		}
		int size = Math.round(16 * ICON_SCALE);
		int ix = x0 + (getWidth() - size) / 2;
		int iy = y0 + 19;
		Matrix3x2fStack pose = graphics.pose();
		pose.pushMatrix();
		pose.translate(ix, iy);
		pose.scale(ICON_SCALE, ICON_SCALE);
		graphics.item(icon, 0, 0);
		pose.popMatrix();
	}

	private void drawTexts(GuiGraphicsExtractor graphics, Font font, int x0, int y0, int x1, boolean on, float a) {
		Matrix3x2fStack pose = graphics.pose();
		int tagWidth = 0;
		if (tag != null) {
			tagWidth = Math.round(font.width(tag) * SMALL) + 6;
			int tx1 = x1 - INSET;
			int tx0 = tx1 - tagWidth;
			NexoShapes.fillRoundedFast(graphics, tx0, y0 + 6, tx1, y0 + 15, NexoStyle.fade(NexoStyle.LINE, a), 2);
			pose.pushMatrix();
			pose.scale(SMALL, SMALL);
			graphics.text(font, tag, Math.round((tx0 + 3) / SMALL), Math.round((y0 + 8) / SMALL),
					NexoStyle.fade(NexoStyle.MUTED, a));
			pose.popMatrix();
		}

		int nameColor = on ? NexoStyle.TEXT_PRIMARY : NexoStyle.TEXT_SECONDARY;
		int nameX = x0 + INSET + 2;
		int nameMax = x1 - INSET - (tagWidth > 0 ? tagWidth + 4 : 0) - nameX;
		graphics.text(font, boldEllipsized(font, getMessage().getString(), nameMax), nameX, y0 + 7,
				NexoStyle.fade(nameColor, a));

		// One line of description at 3/4 size, under the icon.
		int maxPx = Math.round((getWidth() - INSET * 2 - 4) / SMALL);
		pose.pushMatrix();
		pose.scale(SMALL, SMALL);
		graphics.text(font, ellipsize(font, description.getString(), maxPx),
				Math.round(nameX / SMALL), Math.round((y0 + 46) / SMALL), NexoStyle.fade(NexoStyle.MUTED, a));
		pose.popMatrix();
	}

	/** The settings button: a small field with a two-slider glyph, drawn from fills (no icon font). */
	private void drawGear(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		if (onConfigure == null) {
			return;
		}
		int gx0 = getX() + INSET;
		int gy0 = barTop();
		int gx1 = gx0 + BAR_HEIGHT;
		int gy1 = gy0 + BAR_HEIGHT;
		boolean hot = overGear(mouseX, mouseY);
		smallBox(graphics, gx0, gy0, gx1, gy1, NexoStyle.fade(NexoStyle.FIELD, a),
				NexoStyle.fade(hot ? NexoStyle.ACCENT : NexoStyle.CONTROL, a * a));
		int glyph = NexoStyle.fade(hot ? NexoStyle.TEXT_PRIMARY : NexoStyle.MUTED, a);
		graphics.fill(gx0 + 3, gy0 + 4, gx1 - 3, gy0 + 5, glyph);
		graphics.fill(gx0 + 3, gy1 - 5, gx1 - 3, gy1 - 4, glyph);
		graphics.fill(gx1 - 6, gy0 + 3, gx1 - 4, gy0 + 6, glyph);
		graphics.fill(gx0 + 4, gy1 - 6, gx0 + 6, gy1 - 3, glyph);
	}

	private void drawBar(GuiGraphicsExtractor graphics, Font font, int mouseX, int mouseY, int x1, boolean on, float a) {
		int bx0 = barLeft();
		int by0 = barTop();
		int bx1 = x1 - INSET;
		int by1 = by0 + BAR_HEIGHT;
		boolean hot = overBar(mouseX, mouseY);
		int accent = NexoStyle.ACCENT;
		int fill = on ? NexoStyle.mix(NexoStyle.FIELD, accent, hot ? 0.32F : 0.22F)
				: (hot ? NexoStyle.mix(NexoStyle.FIELD, accent, 0.08F) : NexoStyle.FIELD);
		int edge = on ? accent : (hot ? NexoStyle.mix(NexoStyle.CONTROL, accent, 0.6F) : NexoStyle.CONTROL);
		smallBox(graphics, bx0, by0, bx1, by1, NexoStyle.fade(fill, a), NexoStyle.fade(edge, a * a));

		Component label = Component.translatable(on ? "nexomod.qol.card.enabled" : "nexomod.qol.card.disabled")
				.withStyle(style -> style.withBold(true));
		Matrix3x2fStack pose = graphics.pose();
		pose.pushMatrix();
		pose.scale(SMALL, SMALL);
		graphics.centeredText(font, label, Math.round((bx0 + bx1) / 2F / SMALL), Math.round((by0 + 4) / SMALL),
				NexoStyle.fade(on ? NexoStyle.TEXT_PRIMARY : NexoStyle.MUTED, a));
		pose.popMatrix();
	}

	/**
	 * A 1px-outlined box with the fast fill: at radius 3 the anti-aliased corners
	 * don't show, and every card draws two of these each frame.
	 */
	private static void smallBox(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1, int fill, int edge) {
		NexoShapes.fillRoundedFast(graphics, x0, y0, x1, y1, edge, 3);
		NexoShapes.fillRoundedFast(graphics, x0 + 1, y0 + 1, x1 - 1, y1 - 1, fill, 2);
	}

	/** {@code text} in bold, trimmed with "…" to {@code maxPx} as measured in bold — bold glyphs are a pixel wider. */
	private static Component boldEllipsized(Font font, String text, int maxPx) {
		Component full = bold(text);
		if (font.width(full) <= maxPx) {
			return full;
		}
		String cut = text;
		while (!cut.isEmpty() && font.width(bold(cut + "…")) > maxPx) {
			cut = cut.substring(0, cut.length() - 1);
		}
		return bold(cut.stripTrailing() + "…");
	}

	private static Component bold(String text) {
		return Component.literal(text).withStyle(style -> style.withBold(true));
	}

	private static String ellipsize(Font font, String text, int maxPx) {
		if (font.width(text) <= maxPx) {
			return text;
		}
		return font.plainSubstrByWidth(text, Math.max(0, maxPx - font.width("…"))).stripTrailing() + "…";
	}

	/** 0 before this card's turn, 1 once it has arrived, ease-out cubic in between. */
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

	/** Name, on/off state and description — the card shows all three, so the narrator should too. */
	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
		defaultButtonNarrationText(output);
		output.add(NarratedElementType.HINT, Component.translatable(
				state.getAsBoolean() ? "nexomod.qol.card.enabled" : "nexomod.qol.card.disabled")
				.append(". ").append(description));
	}
}
