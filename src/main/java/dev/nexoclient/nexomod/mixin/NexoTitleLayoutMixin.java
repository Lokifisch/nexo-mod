package dev.nexoclient.nexomod.mixin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.joml.Matrix3x2fStack;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.LogoRenderer;
import net.minecraft.client.gui.components.PlainTextButton;
import net.minecraft.client.gui.components.SplashRenderer;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;

import dev.nexoclient.nexomod.NexoMod;
import dev.nexoclient.nexomod.hud.NexoHudVisibility;
import dev.nexoclient.nexomod.screen.NexoShapes;
import dev.nexoclient.nexomod.screen.NexoStyle;

/**
 * The title screen from the mockup (design/STYLE.md): a big NEXO wordmark with
 * the logo's rainbow under it at the top left, the main buttons as one
 * left-aligned column, the small icon buttons in a row at the bottom left, and
 * a dark fade from the left edge so all of it reads over any panorama.
 *
 * <p>The layout is re-applied every frame rather than once in {@code init}:
 * other mods (Mod Menu, say) add their own buttons from their own
 * {@code init} injections, in an order this mixin can't rely on, and a button
 * added after a one-off layout would sit where vanilla put it.
 *
 * <p>Essential's buttons (its "Minimal" menu layout — its "Full" layout draws a
 * side menu against vanilla's centred buttons, which this column breaks) are
 * moved into the small-icon row at the bottom left, after vanilla's, each
 * with its notice flags (unread count, NEW) pinned to its corner. Its
 * character preview ({@code UIPlayerProxy}) is hidden while the re-skin is on;
 * its buttons are only moved, never removed.
 *
 * <p>The logo and splash are wrapped with MixinExtras rather than
 * {@code @Redirect}ed: TitleScreen is one of the most mixed-into classes, and
 * two redirects of the same call crash the game at startup, where wrappers
 * chain.
 *
 * <p>Everything here is skipped while {@link NexoHudVisibility#nexoSkinActive()}
 * is false, leaving vanilla's title screen exactly as it was.
 */
@Mixin(TitleScreen.class)
public abstract class NexoTitleLayoutMixin extends Screen {
	private static final int BUTTON_WIDTH = 170;
	private static final int BUTTON_HEIGHT = 20;
	private static final int BUTTON_GAP = 4;
	private static final float WORDMARK_SCALE = 4F;
	/** Under the wordmark. Built once — neither version changes while the game runs. */
	private static final String VERSION_LINE = "NEXO CLIENT  ·  V"
			+ FabricLoader.getInstance().getModContainer("nexomod")
					.map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse("?")
			+ "  ·  MINECRAFT " + SharedConstants.getCurrentVersion().name();

	protected NexoTitleLayoutMixin(Component title) {
		super(title);
	}

	private int nexomod$left() {
		return Math.max(16, width / 12);
	}

	@Inject(method = "extractRenderState", at = @At("HEAD"))
	private void nexomod$layout(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		if (!NexoHudVisibility.nexoSkinActive()) {
			return;
		}
		List<AbstractWidget> big = new ArrayList<>();
		List<AbstractWidget> small = new ArrayList<>();
		List<AbstractWidget> essential = new ArrayList<>();
		List<AbstractWidget> flags = new ArrayList<>();
		for (GuiEventListener child : children()) {
			if (!(child instanceof AbstractWidget widget) || !widget.visible) {
				continue;
			}
			String name = widget.getClass().getName();
			if (name.startsWith("dev.nexoclient.nexomod.title.") || widget instanceof net.minecraft.client.gui.components.PlayerSkinWidget) {
				// The right-hand cards, account chip and player model place themselves.
				continue;
			}
			if (name.startsWith("gg.essential.")) {
				// Essential's character preview is hidden; its notice flags (the
				// red count and the green NEW) ride on their button, see below;
				// everything else of Essential's joins the bottom row.
				if (name.endsWith("UIPlayerProxy")) {
					widget.visible = false;
				} else if (name.endsWith("NoticeFlagProxy")) {
					flags.add(widget);
				} else {
					essential.add(widget);
				}
			} else if (!(widget instanceof PlainTextButton)) {
				// The copyright link (a PlainTextButton) stays at the bottom right.
				// Square icon buttons go in the bottom row; anything wider joins the column.
				if (widget.getWidth() <= 24 && widget.getHeight() <= 24) {
					small.add(widget);
				} else if (widget.getHeight() <= BUTTON_HEIGHT) {
					big.add(widget);
				}
			}
		}

		// Reading order of wherever they are now. Vanilla's own layout and this
		// one both run top to bottom, left to right, so the order is stable from
		// frame to frame.
		Comparator<AbstractWidget> reading = Comparator.comparingInt(AbstractWidget::getY)
				.thenComparingInt(AbstractWidget::getX);
		big.sort(reading);
		small.sort(reading);
		// Essential's in fixed slots, decided the first time each is seen. Sorting them
		// by position every frame would let one that Essential moved for a frame (a
		// new one, or one it just rebuilt) reshuffle the whole row, every frame.
		essential.sort(Comparator.comparingInt((AbstractWidget widget) -> nexomod$essentialSlots.getOrDefault(widget, Integer.MAX_VALUE))
				.thenComparing(reading));
		for (AbstractWidget widget : essential) {
			nexomod$essentialSlots.computeIfAbsent(widget, seen -> nexomod$essentialSlots.size());
		}
		nexomod$logEssential();

		int left = nexomod$left();
		// 40% down, or higher if the column would otherwise run into the icon row
		// at the bottom — but never up into the wordmark block.
		int pitch = BUTTON_HEIGHT + BUTTON_GAP;
		int fit = height - 48 - big.size() * pitch;
		int floor = Math.round(height * 0.14F) + 60;
		int y = Math.max(floor, Math.min(Math.round(height * 0.40F), fit));
		if (y > fit) {
			pitch = BUTTON_HEIGHT + 1;
		}
		for (AbstractWidget widget : big) {
			widget.setPosition(left, y);
			widget.setWidth(BUTTON_WIDTH);
			y += pitch;
		}
		// The small icons along the bottom left: vanilla's first, then Essential's
		// after a wider gap, so the two sets read as two groups.
		// A row that would run past the right edge wraps onto one above it.
		int x = left;
		int rowY = height - 40;
		for (AbstractWidget widget : small) {
			widget.setPosition(x, rowY);
			x += widget.getWidth() + BUTTON_GAP;
		}
		if (!small.isEmpty() && !essential.isEmpty()) {
			x += BUTTON_GAP * 2;
		}
		// Each flag's host is decided once, from where Essential first put it:
		// beside its button, on the same row. After that only this layout moves
		// either of them, so the pairing must not be re-derived from positions.
		for (AbstractWidget flag : flags) {
			nexomod$flagHosts.computeIfAbsent(flag, f -> nexomod$nearest(f, essential));
		}
		for (AbstractWidget widget : essential) {
			if (x + widget.getWidth() > width - 8 && x > left) {
				x = left;
				rowY -= BUTTON_HEIGHT + BUTTON_GAP;
			}
			widget.setPosition(x, rowY + (BUTTON_HEIGHT - widget.getHeight()) / 2);
			x += widget.getWidth() + BUTTON_GAP;
		}
		// A button's flags sit on its top-right corner, stacking leftwards, like
		// an unread badge, instead of taking a slot of their own in the row.
		java.util.Map<AbstractWidget, Integer> stacked = new java.util.IdentityHashMap<>();
		for (AbstractWidget flag : flags) {
			AbstractWidget host = nexomod$flagHosts.get(flag);
			if (host == null) {
				continue;
			}
			int offset = stacked.merge(host, flag.getWidth() + 1, Integer::sum) - flag.getWidth() - 1;
			flag.setPosition(host.getX() + host.getWidth() - flag.getWidth() + 4 - offset, host.getY() - flag.getHeight() + 6);
		}
	}

	/**
	 * Every {@code init} (first open, resize) rebuilds Essential's widgets in
	 * their own places, so the pairing starts again from those — and the old,
	 * no-longer-shown widgets stop being held.
	 */
	@Inject(method = "init", at = @At("TAIL"))
	private void nexomod$resetFlagHosts(CallbackInfo ci) {
		nexomod$flagHosts.clear();
		nexomod$essentialSlots.clear();
	}

	/** Each of Essential's buttons' place in the row, for as long as this layout of the screen lasts. */
	@Unique
	private final java.util.Map<AbstractWidget, Integer> nexomod$essentialSlots = new java.util.IdentityHashMap<>();

	/** Which of Essential's widgets each of its notice flags belongs to — see the layout above. */
	@Unique
	private final java.util.Map<AbstractWidget, AbstractWidget> nexomod$flagHosts = new java.util.IdentityHashMap<>();

	/** The widget whose centre is closest to {@code flag}'s, preferring the same row. */
	@Unique
	private static AbstractWidget nexomod$nearest(AbstractWidget flag, List<AbstractWidget> candidates) {
		AbstractWidget best = null;
		long bestScore = Long.MAX_VALUE;
		int fx = flag.getX() + flag.getWidth() / 2;
		int fy = flag.getY() + flag.getHeight() / 2;
		for (AbstractWidget candidate : candidates) {
			long dx = candidate.getX() + candidate.getWidth() / 2 - fx;
			long dy = candidate.getY() + candidate.getHeight() / 2 - fy;
			// Vertical distance weighs four times as much: a flag is beside its
			// button, so a button straight above or below is never the host.
			long score = dx * dx + 16 * dy * dy;
			if (score < bestScore) {
				bestScore = score;
				best = candidate;
			}
		}
		return best;
	}

	@Unique
	private boolean nexomod$essentialLogged;

	/**
	 * Once per title screen, every Essential widget's class and bounds, so the
	 * one that draws its character can be told apart from its buttons if the
	 * size rule above ever stops catching it.
	 */
	@Unique
	private void nexomod$logEssential() {
		if (nexomod$essentialLogged) {
			return;
		}
		nexomod$essentialLogged = true;
		for (GuiEventListener child : children()) {
			if (child instanceof AbstractWidget widget && widget.getClass().getName().startsWith("gg.essential.")) {
				NexoMod.LOGGER.info("title screen: Essential widget {} at {},{} size {}x{} visible={}",
						widget.getClass().getName(), widget.getX(), widget.getY(),
						widget.getWidth(), widget.getHeight(), widget.visible);
			}
		}
	}

	/** The dark fade from the left edge, under the buttons — drawn just before the widgets. */
	@Inject(method = "extractRenderState", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/screens/Screen;extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V"))
	private void nexomod$shade(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
		if (!NexoHudVisibility.nexoSkinActive()) {
			return;
		}
		// fillGradient only runs top to bottom, so the left-to-right fade is strips.
		int span = Math.max(1, Math.round(width * 0.6F));
		for (int sx = 0; sx < span; sx += 4) {
			float t = sx / (float) span;
			int alpha = Math.round(0xD0 * (1F - t) * (1F - t));
			graphics.fill(sx, 0, Math.min(sx + 4, span), height, (alpha << 24) | 0x06060A);
		}
	}

	/** The vanilla MINECRAFT logo becomes the NEXO wordmark at the top left. */
	@WrapOperation(method = "extractRenderState", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/components/LogoRenderer;extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IF)V"))
	private void nexomod$wordmark(LogoRenderer logo, GuiGraphicsExtractor graphics, int screenWidth, float alpha,
			Operation<Void> original) {
		if (!NexoHudVisibility.nexoSkinActive()) {
			original.call(logo, graphics, screenWidth, alpha);
			return;
		}
		int left = nexomod$left();
		int top = Math.round(height * 0.14F);
		Font font = this.font;
		Component wordmark = Component.literal("NEXO").withStyle(style -> style.withBold(true));

		Matrix3x2fStack pose = graphics.pose();
		pose.pushMatrix();
		pose.translate(left, top);
		pose.scale(WORDMARK_SCALE, WORDMARK_SCALE);
		graphics.text(font, wordmark, 0, 0, NexoStyle.fade(NexoStyle.TEXT_PRIMARY, alpha), false);
		pose.popMatrix();

		int underlineTop = top + Math.round(9 * WORDMARK_SCALE) + 2;
		NexoShapes.glow(graphics, left, underlineTop, left + 48, underlineTop + 2, NexoStyle.ACCENT, 1, 0.25F * alpha);
		NexoShapes.fillLogoRamp(graphics, left, left + 48, left + 48, underlineTop, underlineTop + 2, Math.round(0xFF * alpha));

		pose.pushMatrix();
		pose.translate(left, underlineTop + 8);
		pose.scale(0.75F, 0.75F);
		graphics.text(font, VERSION_LINE, 0, 0, NexoStyle.fade(NexoStyle.MUTED, alpha));
		pose.popMatrix();
	}

	/** No splash text: it was placed against the centred logo, which is gone. */
	@WrapWithCondition(method = "extractRenderState", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/components/SplashRenderer;extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;ILnet/minecraft/client/gui/Font;F)V"))
	private boolean nexomod$splash(SplashRenderer splash, GuiGraphicsExtractor graphics, int screenWidth, Font font, float alpha) {
		return !NexoHudVisibility.nexoSkinActive();
	}
}
