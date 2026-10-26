package dev.nexoclient.nexomod.tactical.screen;

import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import dev.nexoclient.nexomod.screen.NexoConfig;
import dev.nexoclient.nexomod.screen.NexoIntSlider;
import dev.nexoclient.nexomod.screen.NexoModalScreen;
import dev.nexoclient.nexomod.screen.NexoModuleRow;
import dev.nexoclient.nexomod.screen.NexoStyle;

/** Toggle plus how far ahead to simulate — the only two things the preview has. */
public class NexoTrajectoryConfigScreen extends NexoModalScreen {
	private static final int ROW_WIDTH = 260;

	public NexoTrajectoryConfigScreen(Screen parent) {
		super(Component.translatable("nexomod.qol.trajectory"), parent);
	}

	@Override
	protected void init() {
		super.init();
		layout.defaultCellSetting().alignHorizontallyCenter();
		layout.addChild(new StringWidget(
				title.copy().withStyle(style -> style.withColor(NexoStyle.TEXT_ACTIVE_ACCENT).withBold(true)), font));

		NexoConfig config = NexoConfig.get();
		layout.addChild(new NexoModuleRow(0, 0, ROW_WIDTH, 36,
				Component.translatable("nexomod.qol.enabled"),
				Component.translatable("nexomod.qol.trajectory.description"),
				config::trajectoryEnabled,
				() -> {
					config.setTrajectoryEnabled(!config.trajectoryEnabled());
					minecraft.gui.setScreen(new NexoTrajectoryConfigScreen(parent));
				}));
		layout.addChild(new NexoIntSlider(0, 0, ROW_WIDTH, 20, "nexomod.qol.trajectory.ticks",
				NexoConfig.TRAJECTORY_TICKS_MIN, NexoConfig.TRAJECTORY_TICKS_MAX,
				config.trajectoryTicks(), config::setTrajectoryTicks));

		finishLayout();
	}
}
