package dev.nexoclient.nexomod.screen;

import java.util.function.Supplier;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * Nexo's settings hub — a structural port of vanilla's {@code OptionsScreen}
 * (title header, two-column grid of category buttons, Done footer), built from
 * plain vanilla widgets so the global reskin mixins style it like every other
 * menu. Categories open {@link NexoOptionScreen}-based sub-screens; simple
 * standalone toggles sit directly in the grid, the way vanilla mixes both.
 */
public class NexoSettingsScreen extends Screen {
	private static final Component TITLE = Component.translatable("nexomod.settings.title");

	private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this);
	private final Screen lastScreen;

	public NexoSettingsScreen(Screen lastScreen) {
		super(TITLE);
		this.lastScreen = lastScreen;
	}

	@Override
	protected void init() {
		layout.addTitleHeader(TITLE, font);

		GridLayout grid = new GridLayout();
		grid.defaultCellSetting().paddingHorizontal(4).paddingBottom(4).alignHorizontallyCenter();
		GridLayout.RowHelper helper = grid.createRowHelper(2);
		helper.addChild(openScreenButton(Component.translatable("nexomod.settings.appearance"), () -> new NexoAppearanceScreen(this)));
		helper.addChild(openScreenButton(Component.translatable("nexomod.settings.positionObscuring"), () -> new NexoPositionObscuringScreen(this)));
		helper.addChild(openScreenButton(Component.translatable("nexomod.settings.chat"), () -> new NexoChatScreen(this)));
		helper.addChild(openScreenButton(Component.translatable("nexomod.settings.servers"), () -> new NexoQuickServerScreen(this)));
		// Bedrock Holes used to be named here. It lives in src/full now and this
		// class is compiled into the light jar too, so it arrives through the
		// registry instead — empty in light, one entry in full. Macros stayed
		// in src/main and are still named directly. See NexoExtraCategories.
		for (NexoExtraCategories.Category category : NexoExtraCategories.categories()) {
			helper.addChild(openScreenButton(category.label(), () -> category.factory().apply(this)));
		}
		helper.addChild(openScreenButton(Component.translatable("nexomod.settings.macros"), () -> new NexoMacroListScreen(this)));
		helper.addChild(CycleButton.onOffBuilder(NexoConfig.get().discordRpcEnabled())
				.create(0, 0, 150, 20, Component.translatable("nexomod.settings.discordRpc"), (button, value) -> {
					NexoConfig.get().setDiscordRpcEnabled(value);
					if (!value) {
						dev.nexoclient.nexomod.discord.DiscordRichPresence.get().clearActivity();
					}
				}));
		layout.addToContents(grid);

		layout.addToFooter(Button.builder(CommonComponents.GUI_DONE, button -> onClose()).width(200).build());
		layout.visitWidgets(this::addRenderableWidget);
		repositionElements();
	}

	@Override
	protected void repositionElements() {
		layout.arrangeElements();
	}

	@Override
	public void onClose() {
		minecraft.setScreen(lastScreen);
	}

	private Button openScreenButton(Component message, Supplier<Screen> screen) {
		return Button.builder(message, button -> minecraft.setScreen(screen.get())).build();
	}
}
