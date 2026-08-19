package dev.nexoclient.nexomod.screen;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.layouts.FrameLayout;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

import dev.nexoclient.nexomod.hud.NexoPotatoMode;
import dev.nexoclient.nexomod.hud.NexoQolMenu;

/**
 * The right-shift QoL menu: a small floating panel of feature toggles over
 * live gameplay, not a full menu screen.
 *
 * <p>Three things set this apart from every other Nexo popup:
 * <ul>
 *   <li>{@link #isPauseScreen()} is false — the world keeps ticking behind
 *   it, unlike a normal pause-adjacent screen.</li>
 *   <li>{@code extractBackground} is deliberately <em>not</em> overridden —
 *   vanilla's own default (blur, then its usual dark tint, the same
 *   treatment the in-game Options screen gets) is exactly the "blurred, not
 *   replaced by anything" look this is going for. Not a
 *   {@link NexoModalScreen}: that dims behind a parent screen it redraws,
 *   which is a different mechanism producing a different effect.</li>
 *   <li>This class is on {@code NeonMenuBackgroundMixin}'s deny-list, so the
 *   neon reskin's animated background does not replace vanilla's blur
 *   here — the whole point is the real (blurred) game showing through.</li>
 * </ul>
 *
 * <p>The panel background is drawn ahead of {@code super.extractRenderState}
 * in the same order {@link NexoModalScreen} uses: {@link NexoPanelRenderer}
 * first, then the buttons render on top of it.
 */
public class NexoQolOverlayScreen extends Screen {
	private static final int ROW_WIDTH = 220;
	private static final int ROW_HEIGHT = 36;
	private static final int COLUMNS = 2;

	private final GridLayout layout = new GridLayout();

	public NexoQolOverlayScreen() {
		super(Component.translatable("nexomod.qol.title"));
	}

	@Override
	protected void init() {
		layout.defaultCellSetting().paddingHorizontal(4).paddingBottom(4);
		GridLayout.RowHelper helper = layout.createRowHelper(COLUMNS);

		NexoConfig config = NexoConfig.get();
		helper.addChild(new NexoModuleRow(0, 0, ROW_WIDTH, ROW_HEIGHT,
				Component.translatable("nexomod.qol.keystrokes"),
				Component.translatable("nexomod.qol.keystrokes.description"),
				config::keystrokesHudEnabled,
				() -> minecraft.setScreen(new NexoKeystrokesConfigScreen(this))));
		helper.addChild(new NexoModuleRow(0, 0, ROW_WIDTH, ROW_HEIGHT,
				Component.translatable("nexomod.qol.cps"),
				Component.translatable("nexomod.qol.cps.description"),
				config::cpsCounterEnabled,
				() -> minecraft.setScreen(new NexoCpsConfigScreen(this))));
		helper.addChild(new NexoModuleRow(0, 0, ROW_WIDTH, ROW_HEIGHT,
				Component.translatable("nexomod.settings.armorHud.enabled"),
				Component.translatable("nexomod.qol.armorHud.description"),
				config::armorHudEnabled,
				() -> minecraft.setScreen(new NexoArmorHudConfigScreen(this))));
		// Toggles directly on click rather than opening a config screen — there is
		// nothing to configure, and a quick single click is the point before AFK.
		helper.addChild(new NexoModuleRow(0, 0, ROW_WIDTH, ROW_HEIGHT,
				Component.translatable("nexomod.qol.potatoMode"),
				Component.translatable("nexomod.qol.potatoMode.description"),
				NexoPotatoMode::active,
				NexoPotatoMode::toggle));
		helper.addChild(new NexoModuleRow(0, 0, ROW_WIDTH, ROW_HEIGHT,
				Component.translatable("nexomod.stats.title"),
				Component.translatable("nexomod.stats.description"),
				config::statsHudEnabled,
				() -> minecraft.setScreen(new NexoStatsConfigScreen(this))));
		helper.addChild(new NexoModuleRow(0, 0, ROW_WIDTH, ROW_HEIGHT,
				Component.translatable("nexomod.qol.potion"),
				Component.translatable("nexomod.qol.potion.description"),
				config::potionHudEnabled,
				() -> minecraft.setScreen(new NexoPotionConfigScreen(this))));
		helper.addChild(new NexoModuleRow(0, 0, ROW_WIDTH, ROW_HEIGHT,
				Component.translatable("nexomod.qol.combo"),
				Component.translatable("nexomod.qol.combo.description"),
				config::comboCounterEnabled,
				() -> minecraft.setScreen(new NexoComboConfigScreen(this))));
		helper.addChild(new NexoModuleRow(0, 0, ROW_WIDTH, ROW_HEIGHT,
				Component.translatable("nexomod.qol.actionbarLog"),
				Component.translatable("nexomod.qol.actionbarLog.description"),
				config::actionbarLogEnabled,
				() -> minecraft.setScreen(new NexoActionbarLogConfigScreen(this))));
		helper.addChild(new NexoModuleRow(0, 0, ROW_WIDTH, ROW_HEIGHT,
				Component.translatable("nexomod.qol.pickupLog"),
				Component.translatable("nexomod.qol.pickupLog.description"),
				config::pickupLogEnabled,
				() -> minecraft.setScreen(new NexoPickupLogConfigScreen(this))));
		addModuleRows(helper);
		helper.addChild(Button.builder(Component.translatable("nexomod.qol.editLayout"),
						button -> minecraft.setScreen(new NexoHudEditorScreen(this)))
				.size(ROW_WIDTH, 20).build());

		repositionElements();
		layout.visitWidgets(this::addRenderableWidget);
	}

	/** Extra module rows contributed by other parts of the mod — see {@link NexoQolModules}. */
	private void addModuleRows(GridLayout.RowHelper helper) {
		for (NexoQolModules.Entry entry : NexoQolModules.entries()) {
			helper.addChild(new NexoModuleRow(0, 0, ROW_WIDTH, ROW_HEIGHT,
					entry.name(), entry.description(), entry.enabled(),
					() -> minecraft.setScreen(entry.openConfig().apply(this))));
		}
	}

	@Override
	protected void repositionElements() {
		layout.arrangeElements();
		FrameLayout.centerInRectangle(layout, getRectangle());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		int x0 = layout.getX() - 20;
		int y0 = layout.getY() - 14;
		int x1 = layout.getX() + layout.getWidth() + 20;
		int y1 = layout.getY() + layout.getHeight() + 14;
		NexoPanelRenderer.draw(graphics, x0, y0, x1, y1);
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (NexoQolMenu.isToggleKey(event)) {
			onClose();
			return true;
		}
		return super.keyPressed(event);
	}
}
