package dev.nexoclient.nexomod.hud;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudStatusBarHeightRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.equipment.EquipmentAsset;
import net.minecraft.world.item.equipment.EquipmentAssets;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraft.world.item.equipment.trim.ArmorTrim;

import dev.nexoclient.nexomod.NexoMod;
import dev.nexoclient.nexomod.screen.NexoConfig;

/**
 * A replacement for vanilla's armour bar that says what the armour is made of,
 * what it is enchanted with, and what just hit you.
 *
 * <p>Vanilla draws ten identical grey shields. That tells you how much armour
 * you have and nothing else — not the material, not that a piece is one hit
 * from breaking, not whether the Protection IV you paid for is doing anything.
 * Each of those is already in the player's own inventory; this is the same
 * information where the eye already looks.
 *
 * <h2>Provenance</h2>
 *
 * <p>The icon atlas and the shape of the point maths come from Detail Armor
 * Bar (MIT, © 2021 RedLime) — see {@code THIRD-PARTY-NOTICES.md}, which now
 * ships in the jar root because that licence asks its notice to travel with
 * every copy and a release jar is a copy. The rendering is
 * written from scratch: 26.1 has no {@code Tessellator}, no
 * {@code RenderSystem.setShader} and no {@code BufferRenderer}, so nothing of
 * that layer survived the port. Fresh Armor Bar (LGPL-3.0) contributed the
 * idea of reacting to the damage type and nothing else; its source was not
 * read, for the same reason {@code Essential-Mod/} is not read.
 *
 * <h2>Why {@code replaceElement} and not a mixin</h2>
 *
 * <p>Vanilla's armour drawing is a private static inside {@code Gui}, but
 * Fabric already wraps it and publishes it as
 * {@link VanillaHudElements#ARMOR_BAR}. Replacing the element keeps the
 * original in hand, so the toggle asks the config per frame and switching it
 * off restores vanilla's bar exactly rather than leaving a hole — the same
 * reasoning {@link NexoHudCleaner} documents, and the reason this whole
 * feature needs no mixin.
 *
 * <h2>Where it draws</h2>
 *
 * <p>{@code HudStatusBarHeightRegistry.getHeight(ARMOR_BAR)} is
 * {@code 39 + (everything stacked below the armour bar)}, so subtracting it
 * from the screen height lands on vanilla's own row — including the
 * half-second lag vanilla's health bar keeps after a hit, and including a
 * status bar some other mod inserted underneath. Recomputing that from heart
 * rows here would be a second copy of vanilla's arithmetic that silently
 * drifts; Detail Armor Bar does exactly that, which is why it needs an
 * explicit "compatible with heart mods" option and this does not.
 */
public final class NexoArmorBar implements HudElement {
	private static final Identifier ATLAS =
			Identifier.fromNamespaceAndPath(NexoMod.MOD_ID, "textures/gui/armor_bar.png");
	private static final int ATLAS_SIZE = 128;

	private static final int ICON = 9;
	private static final int ICON_SPACING = 8;
	private static final int ICONS_PER_ROW = NexoArmorBarLayout.ICONS_PER_ROW;

	/** Effect row: outlines, the empty cell and the elytra. */
	private static final int V_EFFECT = 0;
	private static final int U_OUTLINE_FULL = 9;
	private static final int U_OUTLINE_RIGHT = 18;
	private static final int U_OUTLINE_LEFT = 27;
	private static final int U_ELYTRA = 36;
	private static final int U_EMPTY = 45;

	private static final int V_THORNS = 18;
	private static final int U_THORNS_HALF = 27;
	private static final int U_THORNS_FULL = 36;

	/**
	 * The protection aura, twelve frames of a swirl travelling round the shield.
	 * Split across three rows because a half icon needs the half of the swirl
	 * that belongs to it, not the whole thing squeezed over.
	 */
	private static final int V_AURA_FULL = 27;
	private static final int V_AURA_RIGHT = 36;
	private static final int V_AURA_LEFT = 45;
	private static final int AURA_FRAMES = 12;

	/**
	 * The bar icons themselves. The atlas carries a second, chunkier set at
	 * {@code v=9}; this row is the one that reads as vanilla at a glance, which
	 * is what a status bar has to do mid-fight.
	 */
	private static final int V_BAR = 54;

	private static final int WHITE = 0xFFFFFFFF;

	/**
	 * A material's full-icon column. The left-half cell always sits nine pixels
	 * to its left, and the right half is that same cell mirrored.
	 *
	 * @param splittable false for cells the atlas has no half of — the elytra
	 *                   and the empty spacer. Both are aligned to an even point
	 *                   so a half is never asked for, and this makes that a
	 *                   guarantee rather than a coincidence.
	 */
	private record Style(int u, int v, int tint, boolean splittable) {
		Style(int u, int tint) {
			this(u, V_BAR, tint, true);
		}
	}

	private static final Style IRON = new Style(63, WHITE);
	private static final Style SPACER = new Style(U_EMPTY, V_EFFECT, WHITE, false);
	private static final Style ELYTRA = new Style(U_ELYTRA, V_EFFECT, WHITE, false);

	/**
	 * Copper armour has no cell of its own, so it borrows iron's — which is
	 * white, and therefore tints cleanly to anything. That is also the fallback
	 * for modded armour: a neutral grey shield is wrong but legible, where a
	 * missing cell would be an invisible bar.
	 */
	private static final Map<ResourceKey<EquipmentAsset>, Style> MATERIALS = Map.of(
			EquipmentAssets.NETHERITE, new Style(9, WHITE),
			EquipmentAssets.DIAMOND, new Style(27, WHITE),
			EquipmentAssets.TURTLE_SCUTE, new Style(45, WHITE),
			EquipmentAssets.IRON, IRON,
			EquipmentAssets.CHAINMAIL, new Style(81, WHITE),
			EquipmentAssets.GOLD, new Style(99, WHITE),
			EquipmentAssets.COPPER, new Style(63, 0xFFC77B4E));

	/** Leather takes its dye, so its column is looked up rather than mapped. */
	private static final int U_LEATHER = 117;

	private static final int PROTECTION_COLOR = 0xFF99FFFF;
	private static final int PROJECTILE_COLOR = 0xFF7033AD;
	private static final int BLAST_COLOR = 0xFFFFFF00;
	private static final int FIRE_COLOR = 0xFFD23800;
	/** Detail Armor Bar's aura opacity, kept because it reads as a glow rather than a coat of paint. */
	private static final int AURA_ALPHA = 0xCC000000;

	private static final int LOW_DURABILITY_COLOR = 0xFF1919;
	/** One full cycle of a pulse, in ticks. Detail Armor Bar's "normal" speed. */
	private static final int PULSE_TICKS = 30;

	/** How far the sheen travels per tick, and how wide its falloff is, in pixels. */
	private static final float GLINT_SPEED = 1.8F;
	private static final float GLINT_WIDTH = 7F;
	private static final int GLINT_PERIOD_TICKS = 60;

	private final HudElement original;

	private NexoArmorBar(HudElement original) {
		this.original = original;
	}

	public static void register() {
		HudElementRegistry.replaceElement(VanillaHudElements.ARMOR_BAR, NexoArmorBar::new);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker delta) {
		NexoConfig config = NexoConfig.get();
		// Delegated rather than skipped: with the feature off the player gets
		// vanilla's bar back, not a gap where it used to be.
		if (!config.armorBarEnabled()) {
			original.extractRenderState(graphics, delta);
			return;
		}
		if (NexoHudVisibility.hidden()) {
			return;
		}
		Minecraft client = Minecraft.getInstance();
		// The camera's player, not the local one — vanilla draws the armour of
		// whoever you are looking through, which is a different person while
		// spectating. Reading client.player here would blank the bar exactly
		// then. Vanilla's own extractPlayerHealth returns early when this is
		// null, so by the time this element runs it never is.
		if (!(client.getCameraEntity() instanceof Player player) || client.options.hideGui) {
			return;
		}
		draw(graphics, player, config);
	}

	// -- the point list ---------------------------------------------------------

	/** A worn piece, resolved once per frame so nothing below re-reads components. */
	private record Piece(ItemStack stack, EquipmentSlot slot, int defense, Style style) {
	}

	private static List<Piece> pieces(Player player, NexoConfig config) {
		List<Piece> worn = new ArrayList<>(6);
		int armorPoints = 0;
		ItemStack elytra = ItemStack.EMPTY;

		for (EquipmentSlot slot : EquipmentSlot.VALUES) {
			ItemStack stack = player.getItemBySlot(slot);
			Equippable equippable = stack.get(DataComponents.EQUIPPABLE);
			// A shield in the off hand is equippable-ish but not *for* that slot;
			// only a piece worn where it belongs contributes armour.
			if (equippable == null || equippable.slot() != slot) {
				continue;
			}
			if (equippable.assetId().map(EquipmentAssets.ELYTRA::equals).orElse(false)) {
				elytra = stack;
				continue;
			}
			int defense = armorPoints(stack, slot);
			if (defense > 0) {
				worn.add(new Piece(stack, slot, defense, config.armorBarMaterials() ? style(stack, equippable) : IRON));
				armorPoints += defense;
			}
		}

		// The elytra grants no armour, so it rides along as two borrowed points.
		// Only ever alongside real armour: on its own the bar would have no
		// armour points at all, Fabric's height provider would report zero, and
		// the pip would land on top of the health bar.
		if (!elytra.isEmpty() && armorPoints > 0) {
			if (armorPoints % 2 == 1) {
				// Pad to an even point so the elytra owns a whole icon — the
				// atlas has no half of it to draw.
				worn.add(new Piece(ItemStack.EMPTY, EquipmentSlot.CHEST, 1, SPACER));
			}
			worn.add(new Piece(elytra, EquipmentSlot.CHEST, 2, ELYTRA));
		}
		return worn;
	}

	/**
	 * The armour points a piece grants in that slot, read off its own attribute
	 * modifiers rather than assumed from its item type — which is what makes
	 * modded armour work without knowing anything about it.
	 */
	private static int armorPoints(ItemStack stack, EquipmentSlot slot) {
		int[] points = {0};
		stack.forEachModifier(slot, (attribute, modifier) -> {
			if (Attributes.ARMOR.equals(attribute) && modifier.operation() == AttributeModifier.Operation.ADD_VALUE) {
				points[0] += (int) modifier.amount();
			}
		});
		return Math.max(points[0], 0);
	}

	private static Style style(ItemStack stack, Equippable equippable) {
		ResourceKey<EquipmentAsset> asset = equippable.assetId().orElse(null);
		if (asset == null) {
			return IRON;
		}
		if (EquipmentAssets.LEATHER.equals(asset)) {
			return new Style(U_LEATHER, 0xFF000000 | DyedItemColor.getOrDefault(stack, DyedItemColor.LEATHER_COLOR));
		}
		return MATERIALS.getOrDefault(asset, IRON);
	}

	// -- drawing ----------------------------------------------------------------

	private static void draw(GuiGraphicsExtractor graphics, Player player, NexoConfig config) {
		List<Piece> worn = pieces(player, config);
		if (worn.isEmpty()) {
			return;
		}
		int[] defense = new int[worn.size()];
		for (int i = 0; i < worn.size(); i++) {
			defense[i] = worn.get(i).defense();
		}
		int[] owner = NexoArmorBarLayout.owners(defense);
		if (owner.length == 0) {
			return;
		}
		int[] halves = NexoArmorBarLayout.visibleHalves(owner);

		int left = graphics.guiWidth() / 2 - 91;
		int top = graphics.guiHeight() - HudStatusBarHeightRegistry.getHeight(VanillaHudElements.ARMOR_BAR);
		long ticks = NexoArmorBarEffects.ticks();

		// Damage feedback shakes the whole bar, so it is applied as an offset to
		// every draw below rather than as a pass of its own.
		int shake = config.armorBarDamageFeedback() ? NexoArmorBarEffects.shake(ticks) : 0;
		top += shake;

		drawIcons(graphics, halves, worn, left, top);
		drawOverflowPips(graphics, owner, worn, left, top);

		if (config.armorBarEnchants()) {
			drawProtection(graphics, worn, left, top, ticks);
		}
		if (config.armorBarThorns()) {
			drawThorns(graphics, worn, left, top);
		}
		if (config.armorBarDetail()) {
			drawTrims(graphics, halves, worn, left, top);
			drawGlint(graphics, halves, worn, left, top, ticks);
		}
		if (config.armorBarDurability()) {
			drawLowDurability(graphics, halves, worn, left, top, ticks);
		}
		if (config.armorBarMending()) {
			drawMending(graphics, halves, left, top, ticks);
		}
		if (config.armorBarDamageFeedback()) {
			drawDamageFeedback(graphics, halves, left, top, ticks);
		}
	}

	private static void drawIcons(GuiGraphicsExtractor graphics, int[] halves, List<Piece> worn, int left, int top) {
		for (int i = 0; i < ICONS_PER_ROW; i++) {
			int x = left + i * ICON_SPACING;
			int leftOwner = halves[i * 2];
			int rightOwner = halves[i * 2 + 1];

			if (leftOwner < 0) {
				cell(graphics, U_EMPTY, V_EFFECT, x, top, false, WHITE);
				continue;
			}
			Style leftStyle = worn.get(leftOwner).style();
			if (leftOwner == rightOwner || !leftStyle.splittable()) {
				cell(graphics, leftStyle.u(), leftStyle.v(), x, top, false, leftStyle.tint());
				continue;
			}
			// Empty shield underneath, then the right half, then the left over the
			// top of it: the two halves share a pixel column down the middle and
			// the lower-numbered point is the one that should win it.
			cell(graphics, U_EMPTY, V_EFFECT, x, top, false, WHITE);
			if (rightOwner >= 0) {
				half(graphics, worn.get(rightOwner).style(), x, top, true);
			}
			half(graphics, leftStyle, x, top, false);
		}
	}

	/**
	 * Half a shield, mirrored for the right-hand one.
	 *
	 * <p>Every material's half cell sits nine pixels left of its full one, but
	 * the elytra and the empty spacer have no half — and nine pixels left of
	 * those is an unrelated cell, which would draw a sliver of somebody else's
	 * artwork. They are aligned to an even armour point so this cannot come up
	 * in practice; drawing nothing makes that a guarantee instead of a
	 * coincidence, and leaves the empty shield already underneath showing
	 * through.
	 */
	private static void half(GuiGraphicsExtractor graphics, Style style, int x, int y, boolean mirror) {
		if (!style.splittable()) {
			return;
		}
		cell(graphics, style.u() - ICON, style.v(), x, y, mirror, style.tint());
	}

	/**
	 * Rows already filled, as a little stack of shields tucked left of the bar.
	 *
	 * <p>Each pip takes the material of the last point in the row it stands for,
	 * so a stack of netherite does not announce itself in iron.
	 */
	private static void drawOverflowPips(GuiGraphicsExtractor graphics, int[] owner, List<Piece> worn,
			int left, int top) {
		int rows = NexoArmorBarLayout.overflowRows(owner.length);
		for (int i = 0; i < rows; i++) {
			Style style = worn.get(owner[(i + 1) * NexoArmorBarLayout.POINTS_PER_ROW - 1]).style();
			cell(graphics, style.u(), style.v(), left - 7 - (rows - i) * 3, top, false, style.tint());
		}
	}

	/**
	 * The protection enchantments, as a swirl over as many icons as their levels
	 * add up to. Two colours can share one icon where the tally crosses a
	 * boundary, which is what keeps a mixed set readable instead of averaging
	 * into mud.
	 */
	private static void drawProtection(GuiGraphicsExtractor graphics, List<Piece> worn,
			int left, int top, long ticks) {
		Tally generic = tally(worn, Enchantments.PROTECTION);
		// Plain Protection counts its pieces as well as its levels, so a full set
		// of it outweighs a single specialised piece — which is how the damage
		// maths actually behaves.
		int[] levels = {
				generic.level() + generic.count(),
				tally(worn, Enchantments.PROJECTILE_PROTECTION).level(),
				tally(worn, Enchantments.BLAST_PROTECTION).level(),
				tally(worn, Enchantments.FIRE_PROTECTION).level()
		};
		int[] colors = {PROTECTION_COLOR, PROJECTILE_COLOR, BLAST_COLOR, FIRE_COLOR};

		int total = levels[0] + levels[1] + levels[2] + levels[3];
		int frame = (int) ((ticks / 3) % AURA_FRAMES);

		for (int i = 0; i < ICONS_PER_ROW && i * 2 < total; i++) {
			int x = left + i * ICON_SPACING;
			int leftKind = firstNonEmpty(levels);
			if (leftKind < 0) {
				break;
			}
			if (i * 2 + 1 >= total) {
				// Odd tally: the last icon is half lit.
				aura(graphics, x, top, V_AURA_LEFT, frame, colors[leftKind]);
				break;
			}
			levels[leftKind]--;
			int rightKind = firstNonEmpty(levels);
			if (rightKind == leftKind) {
				levels[leftKind]--;
				aura(graphics, x, top, V_AURA_FULL, frame, colors[leftKind]);
			} else {
				aura(graphics, x, top, V_AURA_LEFT, frame, colors[leftKind]);
				if (rightKind >= 0) {
					levels[rightKind]--;
					aura(graphics, x, top, V_AURA_RIGHT, frame, colors[rightKind]);
				}
			}
		}
	}

	private static int firstNonEmpty(int[] levels) {
		for (int i = 0; i < levels.length; i++) {
			if (levels[i] > 0) {
				return i;
			}
		}
		return -1;
	}

	private static void aura(GuiGraphicsExtractor graphics, int x, int y, int row, int frame, int rgb) {
		cell(graphics, frame * ICON, row, x, y, false, AURA_ALPHA | (rgb & 0x00FFFFFF));
	}

	/**
	 * Thorns, as spikes over as many icons as its levels reach.
	 *
	 * <p>Static, with no flash when it procs: a thorns hit is resolved entirely
	 * on the server and the client is never told it happened. Detail Armor Bar
	 * carries the animation code for one, but its trigger is never assigned
	 * anywhere in its source — dead rather than merely unused, and worth not
	 * reimplementing on the same false premise.
	 */
	private static void drawThorns(GuiGraphicsExtractor graphics, List<Piece> worn, int left, int top) {
		Tally thorns = tally(worn, Enchantments.THORNS);
		// Each level past the first counts double, so Thorns III on one piece
		// still spans more of the bar than Thorns I on three.
		int reach = thorns.level() + Math.max(thorns.level() - thorns.count(), 0);
		for (int i = 0; i < ICONS_PER_ROW && i * 2 < reach; i++) {
			int x = left + i * ICON_SPACING;
			boolean half = i * 2 + 1 >= reach;
			cell(graphics, half ? U_THORNS_HALF : U_THORNS_FULL, V_THORNS, x, top, false, WHITE);
		}
	}

	/** An armour trim's own colour, outlined over the icons that piece paid for. */
	private static void drawTrims(GuiGraphicsExtractor graphics, int[] halves, List<Piece> worn, int left, int top) {
		for (int i = 0; i < ICONS_PER_ROW; i++) {
			int leftOwner = halves[i * 2];
			int rightOwner = halves[i * 2 + 1];
			int leftTrim = leftOwner < 0 ? 0 : trimColor(worn.get(leftOwner).stack());
			int rightTrim = rightOwner < 0 ? 0 : trimColor(worn.get(rightOwner).stack());
			if (leftTrim == 0 && rightTrim == 0) {
				continue;
			}
			int x = left + i * ICON_SPACING;
			if (leftTrim == rightTrim) {
				outline(graphics, x, top, U_OUTLINE_FULL, 0x66000000 | leftTrim);
				continue;
			}
			if (leftTrim != 0) {
				outline(graphics, x, top, U_OUTLINE_LEFT, 0x66000000 | leftTrim);
			}
			if (rightTrim != 0) {
				outline(graphics, x, top, U_OUTLINE_RIGHT, 0x66000000 | rightTrim);
			}
		}
	}

	/**
	 * The trim's colour comes from the material's own description style — the
	 * same colour vanilla prints the trim's name in. No table to keep in step,
	 * and a modded trim material gets its own colour for free.
	 */
	private static int trimColor(ItemStack stack) {
		ArmorTrim trim = stack.get(DataComponents.TRIM);
		if (trim == null) {
			return 0;
		}
		TextColor color = trim.material().value().description().getStyle().getColor();
		return color == null ? 0 : color.getValue() & 0x00FFFFFF;
	}

	/** A sheen sweeping the bar while any worn piece is enchanted. */
	private static void drawGlint(GuiGraphicsExtractor graphics, int[] halves, List<Piece> worn,
			int left, int top, long ticks) {
		boolean enchanted = false;
		for (Piece piece : worn) {
			if (!EnchantmentHelper.getEnchantmentsForCrafting(piece.stack()).isEmpty()) {
				enchanted = true;
				break;
			}
		}
		if (!enchanted) {
			return;
		}
		// Starts off the left edge and runs past the right one, so the sweep
		// enters and leaves rather than popping into existence mid-bar.
		float head = -GLINT_WIDTH + (ticks % GLINT_PERIOD_TICKS) * GLINT_SPEED;
		for (int i = 0; i < ICONS_PER_ROW; i++) {
			if (halves[i * 2] < 0) {
				continue;
			}
			float distance = Math.abs(i * ICON_SPACING - head);
			if (distance >= GLINT_WIDTH) {
				continue;
			}
			int alpha = Math.round(Mth.lerp(distance / GLINT_WIDTH, 0.55F, 0F) * 255F);
			if (alpha > 0) {
				outline(graphics, left + i * ICON_SPACING, top, U_OUTLINE_FULL, (alpha << 24) | 0x00FFFFFF);
			}
		}
	}

	/**
	 * A red pulse over as many icons as the nearly-broken pieces are worth,
	 * counted from the right end of the bar inward — the end that disappears
	 * first when a piece actually breaks.
	 */
	private static void drawLowDurability(GuiGraphicsExtractor graphics, int[] halves, List<Piece> worn,
			int left, int top, long ticks) {
		int failing = 0;
		for (Piece piece : worn) {
			if (NexoArmorBarEffects.nearlyBroken(piece.stack())) {
				failing += piece.defense();
			}
		}
		if (failing == 0) {
			return;
		}
		int alpha = pulse(ticks);
		if (alpha == 0) {
			return;
		}
		markFromRight(graphics, halves, left, top, failing, (alpha << 24) | LOW_DURABILITY_COLOR);
	}

	/** A white flash over the whole bar when a piece repairs — mending, or an anvil. */
	private static void drawMending(GuiGraphicsExtractor graphics, int[] halves, int left, int top, long ticks) {
		int alpha = NexoArmorBarEffects.repairAlpha(ticks);
		if (alpha == 0) {
			return;
		}
		for (int i = 0; i < ICONS_PER_ROW; i++) {
			if (halves[i * 2] < 0) {
				continue;
			}
			boolean half = halves[i * 2 + 1] < 0;
			outline(graphics, left + i * ICON_SPACING, top, half ? U_OUTLINE_LEFT : U_OUTLINE_FULL,
					(alpha << 24) | 0x00FFFFFF);
		}
	}

	/**
	 * What just hit you, in the colour of the thing that did it.
	 *
	 * <p>Damage that {@linkplain NexoArmorBarEffects#feedbackColor bypasses
	 * armour} deliberately draws nothing at all. A bar that flashed for
	 * starvation would be saying the armour helped, and the silence is the more
	 * useful signal.
	 */
	private static void drawDamageFeedback(GuiGraphicsExtractor graphics, int[] halves,
			int left, int top, long ticks) {
		int color = NexoArmorBarEffects.feedbackColor(ticks);
		if (color == 0) {
			return;
		}
		for (int i = 0; i < ICONS_PER_ROW; i++) {
			if (halves[i * 2] < 0) {
				continue;
			}
			boolean half = halves[i * 2 + 1] < 0;
			outline(graphics, left + i * ICON_SPACING, top, half ? U_OUTLINE_LEFT : U_OUTLINE_FULL, color);
		}
	}

	/** Paints {@code points} armour points' worth of icons, right to left. */
	private static void markFromRight(GuiGraphicsExtractor graphics, int[] halves,
			int left, int top, int points, int argb) {
		for (int i = ICONS_PER_ROW - 1; i >= 0 && points > 0; i--) {
			int leftOwner = halves[i * 2];
			if (leftOwner < 0) {
				continue;
			}
			int x = left + i * ICON_SPACING;
			boolean half = halves[i * 2 + 1] < 0;
			if (half || points == 1) {
				outline(graphics, x, top, half ? U_OUTLINE_LEFT : U_OUTLINE_RIGHT, argb);
				points--;
			} else {
				outline(graphics, x, top, U_OUTLINE_FULL, argb);
				points -= 2;
			}
		}
	}

	/**
	 * A triangular fade in and out over {@link #PULSE_TICKS}, dark for one cycle
	 * in every two so the pulse reads as a blink rather than a wobble.
	 */
	private static int pulse(long ticks) {
		long phase = ticks % (PULSE_TICKS * 4L);
		if (phase >= PULSE_TICKS * 2L) {
			return 0;
		}
		float within = (ticks % PULSE_TICKS) / (float) (PULSE_TICKS - 1);
		float alpha = phase < PULSE_TICKS
				? Mth.lerp(within, 0F, 0.65F)
				: Mth.lerp(within, 0.65F, 0F);
		return Math.round(alpha * 255F);
	}

	private static void outline(GuiGraphicsExtractor graphics, int x, int y, int u, int argb) {
		cell(graphics, u, V_EFFECT, x, y, false, argb);
	}

	/**
	 * One 9x9 cell of the atlas.
	 *
	 * <p>Mirroring is a negative region width rather than a matrix flip:
	 * {@code blit} divides {@code u + regionWidth} by the texture width to get
	 * the far edge, so a negative one simply swaps the two U coordinates. That
	 * keeps the pose stack untouched, which matters because the HUD is drawn
	 * under whatever transform the caller left in place.
	 */
	private static void cell(GuiGraphicsExtractor graphics, int u, int v, int x, int y, boolean mirror, int argb) {
		graphics.blit(RenderPipelines.GUI_TEXTURED, ATLAS,
				x, y,
				mirror ? u + ICON : u, v,
				ICON, ICON,
				mirror ? -ICON : ICON, ICON,
				ATLAS_SIZE, ATLAS_SIZE,
				argb);
	}

	// -- enchantments -----------------------------------------------------------

	/**
	 * @param level total levels of one enchantment across the worn set
	 * @param count how many pieces carry it
	 */
	private record Tally(int level, int count) {
	}

	private static Tally tally(List<Piece> worn, ResourceKey<net.minecraft.world.item.enchantment.Enchantment> type) {
		int level = 0;
		int count = 0;
		for (Piece piece : worn) {
			for (var entry : EnchantmentHelper.getEnchantmentsForCrafting(piece.stack()).entrySet()) {
				if (entry.getKey().is(type)) {
					level += entry.getIntValue();
					count++;
				}
			}
		}
		return new Tally(level, count);
	}
}
