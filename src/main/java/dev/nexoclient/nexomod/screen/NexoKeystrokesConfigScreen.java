package dev.nexoclient.nexomod.screen;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

import dev.nexoclient.nexomod.hud.NexoKeystrokesConfig;

/**
 * Keystrokes HUD settings: on/off, whether the built-in WASD+click cross
 * shows at all, and the list of custom keys added on top of it — see
 * {@code hud.NexoKeystrokesConfig} for why those are raw key codes.
 *
 * <p>Every action that changes state opens a fresh instance of this screen
 * rather than mutating and re-{@code init}ing the current one — the same
 * "new instance, not a manual rebuild" pattern the other config screens use,
 * since {@code Screen.init()} appends to its widget lists rather than
 * replacing them, and calling it twice on one instance would double them up.
 * The one thing that has to survive that recreation — which entry is
 * mid-rebind — travels through the second constructor instead of a field
 * mutated in place.
 */
public class NexoKeystrokesConfigScreen extends NexoModalScreen {
	private static final int ROW_WIDTH = 260;

	/** Non-null while waiting for the next key press to bind — see {@link #keyPressed}. */
	private final NexoKeystrokesConfig.KeyEntry rebinding;

	public NexoKeystrokesConfigScreen(Screen parent) {
		this(parent, null);
	}

	public NexoKeystrokesConfigScreen(Screen parent, NexoKeystrokesConfig.KeyEntry rebinding) {
		super(Component.translatable("nexomod.qol.keystrokes"), parent);
		this.rebinding = rebinding;
	}

	@Override
	protected void init() {
		super.init();
		layout.defaultCellSetting().alignHorizontallyCenter();
		layout.addChild(new StringWidget(title.copy().withStyle(style -> style.withColor(NexoStyle.TEXT_ACTIVE_ACCENT).withBold(true)), font));

		NexoConfig config = NexoConfig.get();
		layout.addChild(new NexoModuleRow(0, 0, ROW_WIDTH, 36,
				Component.translatable("nexomod.qol.enabled"),
				Component.translatable("nexomod.qol.keystrokes.description"),
				config::keystrokesHudEnabled,
				() -> {
					config.setKeystrokesHudEnabled(!config.keystrokesHudEnabled());
					minecraft.setScreen(new NexoKeystrokesConfigScreen(parent));
				}));

		NexoKeystrokesConfig keys = NexoKeystrokesConfig.get();
		layout.addChild(new NexoModuleRow(0, 0, ROW_WIDTH, 36,
				Component.translatable("nexomod.qol.keystrokes.defaultCluster"),
				Component.translatable("nexomod.qol.keystrokes.defaultCluster.description"),
				keys::showDefaultCluster,
				() -> {
					keys.setShowDefaultCluster(!keys.showDefaultCluster());
					minecraft.setScreen(new NexoKeystrokesConfigScreen(parent));
				}));

		for (NexoKeystrokesConfig.KeyEntry entry : keys.customEntries()) {
			addEntryRow(keys, entry);
		}

		layout.addChild(Button.builder(Component.translatable("nexomod.qol.keystrokes.addKey"), button -> {
					NexoKeystrokesConfig.KeyEntry entry = new NexoKeystrokesConfig.KeyEntry("?", -1);
					keys.addEntry(entry);
					minecraft.setScreen(new NexoKeystrokesConfigScreen(parent, entry));
				})
				.size(ROW_WIDTH, 20).build());
		layout.addChild(Button.builder(Component.translatable("nexomod.qol.editLayout"),
						button -> minecraft.setScreen(new NexoHudEditorScreen(this)))
				.size(ROW_WIDTH, 20).build());

		finishLayout();
	}

	private void addEntryRow(NexoKeystrokesConfig keys, NexoKeystrokesConfig.KeyEntry entry) {
		LinearLayout row = layout.addChild(LinearLayout.horizontal().spacing(4));
		Component label = entry == rebinding
				? Component.translatable("nexomod.qol.keystrokes.pressAnyKey")
				: Component.literal(entry.label);
		row.addChild(Button.builder(label, button -> minecraft.setScreen(new NexoKeystrokesConfigScreen(parent, entry)))
				.size(180, 20).build());
		row.addChild(Button.builder(Component.translatable("nexomod.qol.remove"), button -> {
					keys.removeEntry(entry);
					minecraft.setScreen(new NexoKeystrokesConfigScreen(parent));
				})
				.size(70, 20).build());
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (rebinding != null) {
			InputConstants.Key key = InputConstants.getKey(event);
			if (key.getValue() != InputConstants.KEY_ESCAPE) {
				rebinding.label = key.getDisplayName().getString();
				rebinding.keyCode = key.getValue();
				NexoKeystrokesConfig.get().save();
			}
			minecraft.setScreen(new NexoKeystrokesConfigScreen(parent));
			return true;
		}
		return super.keyPressed(event);
	}
}
