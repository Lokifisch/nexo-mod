package dev.nexoclient.nexomod.screen;

import java.util.function.Consumer;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import dev.nexoclient.nexomod.paperserver.PaperServerOrchestrator.HostOptions;

/**
 * Shown before converting a world to a Paper server — the equivalent of
 * vanilla's "Open to LAN" dialog (game mode, an allow-cheats-equivalent),
 * plus the online-mode tradeoff described on {@code PaperServerFiles}, plus
 * EULA consent. One screen rather than a settings step followed by a
 * separate legal step: everything here is decided before the first click
 * that actually touches disk.
 */
public class NexoPaperHostSettingsScreen extends NexoModalScreen {
	private static final String[] GAME_MODES = {"survival", "creative", "adventure", "spectator"};

	/** Remembered across opens, same convenience {@code OfflineLoginScreen} gives its username field. */
	private static boolean lastGrantOperator = true;
	private static boolean lastOnlineMode = true;

	private final Consumer<HostOptions> onHost;
	private final String playerName;

	private String gameMode;
	private boolean grantOperator = lastGrantOperator;
	private boolean onlineMode = lastOnlineMode;

	/**
	 * @param detectedGameMode the world's actual current gamemode (one of
	 *                         {@link #GAME_MODES}), used to seed the cycle
	 *                         button so hosting a creative world defaults to
	 *                         granting creative rather than always starting
	 *                         from survival.
	 */
	public NexoPaperHostSettingsScreen(Screen parent, String playerName, String detectedGameMode, Consumer<HostOptions> onHost) {
		super(Component.translatable("nexomod.paperServer.hostSettings.title"), parent);
		this.playerName = playerName;
		this.onHost = onHost;
		this.gameMode = detectedGameMode;
	}

	@Override
	protected void init() {
		super.init();
		layout.defaultCellSetting().alignHorizontallyCenter();
		layout.addChild(new StringWidget(title.copy().withStyle(style -> style.withColor(NexoStyle.TEXT_ACTIVE_ACCENT).withBold(true)), font));

		layout.addChild(CycleButton.<String>builder(mode -> Component.translatable("nexomod.paperServer.hostSettings.gameMode." + mode), gameMode)
				.withValues(GAME_MODES)
				.withTooltip(mode -> Tooltip.create(Component.translatable("nexomod.paperServer.hostSettings.gameMode.tooltip")))
				.create(0, 0, 240, 20, Component.translatable("nexomod.paperServer.hostSettings.gameMode"),
						(button, value) -> gameMode = value));

		layout.addChild(CycleButton.onOffBuilder(grantOperator)
				.withTooltip(value -> Tooltip.create(Component.translatable("nexomod.paperServer.hostSettings.grantOperator.tooltip")))
				.create(0, 0, 240, 20, Component.translatable("nexomod.paperServer.hostSettings.grantOperator"),
						(button, value) -> grantOperator = value));

		layout.addChild(CycleButton.onOffBuilder(onlineMode)
				.withTooltip(value -> Tooltip.create(Component.translatable("nexomod.paperServer.hostSettings.onlineMode.tooltip")))
				.create(0, 0, 240, 20, Component.translatable("nexomod.paperServer.hostSettings.onlineMode"),
						(button, value) -> onlineMode = value));

		layout.addChild(new MultiLineTextWidget(Component.translatable("nexomod.paperServer.eula.body"), font)
				.setMaxWidth(280).setCentered(true));
		layout.addChild(new StringWidget(Component.literal("https://aka.ms/MinecraftEULA")
				.withStyle(style -> style.withColor(ChatFormatting.AQUA).withUnderlined(true)), font));

		LinearLayout buttonRow = layout.addChild(LinearLayout.horizontal().spacing(4));
		buttonRow.defaultCellSetting().paddingTop(12);
		buttonRow.addChild(NexoButton.builder(Component.translatable("nexomod.paperServer.hostSettings.host"), this::host).build());
		buttonRow.addChild(NexoButton.builder(CommonComponents.GUI_CANCEL, () -> minecraft.setScreen(parent)).build());

		finishLayout();
	}

	private void host() {
		lastGrantOperator = grantOperator;
		lastOnlineMode = onlineMode;

		minecraft.setScreen(parent);
		onHost.accept(new HostOptions(gameMode, grantOperator, onlineMode, playerName));
	}
}
