package dev.nexoclient.nexomod.hud;

import org.joml.Matrix3x2fStack;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;

/**
 * Turns off vanilla HUD pieces that Nexo already replaces, plus two that are
 * just server-side clutter.
 *
 * <p>Two of the four exist because Nexo draws the same information better and
 * the vanilla original would otherwise sit on top of it: the Actionbar Log
 * already shows overlay messages with a fade and five lines of history, and
 * the Potion HUD already shows effects as named text lines with durations.
 * The other two — the scoreboard sidebar and boss bars — have no Nexo
 * equivalent and are simply removable clutter.
 *
 * <h2>Why {@code replaceElement} and not {@code removeElement}</h2>
 *
 * <p>{@link HudElementRegistry#removeElement} takes an element out of the
 * pipeline at registration time, permanently — a toggle built on it could
 * only apply on the next launch. Wrapping the original in a
 * {@link ConditionalElement} instead keeps it in the pipeline and asks the
 * config <em>per frame</em>, so every toggle here takes effect the moment it
 * is clicked. The cost is one boolean read per hidden element per frame,
 * which is nothing next to what the element itself would have drawn.
 *
 * <p>This is also why the whole module needs no mixin: the Fabric HUD
 * registry already exposes every vanilla element by id.
 */
public final class NexoHudCleaner {
	private NexoHudCleaner() {
	}

	public static void register() {
		for (NexoVanillaHud.Entry entry : NexoVanillaHud.ENTRIES) {
			HudElementRegistry.replaceElement(entry.id(), original -> new ManagedElement(original, entry));
		}
	}

	/**
	 * Delegates to the element it replaced, unless the config says to hide it, and
	 * under a matrix that carries it to wherever it has been dragged.
	 *
	 * <p>Holding the original rather than dropping it is what makes hiding
	 * reversible without a restart, and it is also what makes moving possible at
	 * all: the offset is applied to the pose, so vanilla goes on computing its own
	 * coordinates exactly as before and simply lands somewhere else. That works
	 * for elements whose size we cannot know — a scoreboard is as wide as its
	 * longest line — where an absolute reposition would need a number we do not
	 * have.
	 */
	private record ManagedElement(HudElement original, NexoVanillaHud.Entry entry) implements HudElement {
		@Override
		public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker delta) {
			if (entry.hidden().getAsBoolean()) {
				return;
			}
			int[] offset = entry.offset(graphics.guiWidth(), graphics.guiHeight());
			float scale = entry.scale();
			if (offset[0] == 0 && offset[1] == 0 && scale == 1f) {
				// Untouched: no matrix push at all, so an unmoved HUD costs nothing.
				original.extractRenderState(graphics, delta);
				return;
			}

			Matrix3x2fStack pose = graphics.pose();
			pose.pushMatrix();
			pose.translate(offset[0], offset[1]);
			if (scale != 1f) {
				// Scaled about the element's own corner, so resizing in the editor
				// grows it away from the handle instead of sliding it across the screen.
				ScreenRectangle bounds = entry.resolveBounds(graphics.guiWidth(), graphics.guiHeight());
				pose.translate(bounds.left(), bounds.top());
				pose.scale(scale, scale);
				pose.translate(-bounds.left(), -bounds.top());
			}
			original.extractRenderState(graphics, delta);
			pose.popMatrix();
		}
	}
}
