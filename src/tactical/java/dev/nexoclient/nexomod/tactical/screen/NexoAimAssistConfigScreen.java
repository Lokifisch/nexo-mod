package dev.nexoclient.nexomod.tactical.screen;

import java.util.function.IntConsumer;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import dev.nexoclient.nexomod.screen.NexoConfig;
import dev.nexoclient.nexomod.screen.NexoIntSlider;
import dev.nexoclient.nexomod.screen.NexoModalScreen;
import dev.nexoclient.nexomod.screen.NexoModuleRow;
import dev.nexoclient.nexomod.screen.NexoStyle;

public class NexoAimAssistConfigScreen extends NexoModalScreen {
	private static final int ROW_WIDTH = 260;

	public NexoAimAssistConfigScreen(Screen parent) {
		super(Component.translatable("nexomod.qol.aimAssist"), parent);
	}

	@Override
	protected void init() {
		super.init();
		layout.defaultCellSetting().alignHorizontallyCenter();
		layout.addChild(new StringWidget(title.copy().withStyle(style -> style.withColor(NexoStyle.TEXT_PRIMARY).withBold(true)), font));

		NexoConfig config = NexoConfig.get();
		layout.addChild(new NexoModuleRow(0, 0, ROW_WIDTH, 36,
				Component.translatable("nexomod.qol.enabled"),
				Component.translatable("nexomod.qol.aimAssist.description"),
				config::aimAssistEnabled,
				() -> {
					config.setAimAssistEnabled(!config.aimAssistEnabled());
					minecraft.gui.setScreen(new NexoAimAssistConfigScreen(parent));
				}));
		layout.addChild(new NexoIntSlider(0, 0, ROW_WIDTH, 20, "nexomod.qol.aimAssist.strength",
				1, 100, config.aimAssistStrength(), config::setAimAssistStrength));
		layout.addChild(new NexoIntSlider(0, 0, ROW_WIDTH, 20, "nexomod.qol.aimAssist.fov",
				5, 180, config.aimAssistFov(), config::setAimAssistFov));
		layout.addChild(new NexoIntSlider(0, 0, ROW_WIDTH, 20, "nexomod.qol.aimAssist.range",
				2, 6, config.aimAssistRange(), config::setAimAssistRange));
		layout.addChild(Button.builder(targetsLabel(config), b -> {
			config.cycleAimAssistTargets();
			b.setMessage(targetsLabel(config));
		}).width(ROW_WIDTH).build());
		layout.addChild(Button.builder(pointLabel(config), b -> {
			config.cycleAimAssistPoint();
			b.setMessage(pointLabel(config));
		}).width(ROW_WIDTH).build());
		layout.addChild(Button.builder(onlyLabel(config), b -> {
			config.setAimAssistOnlyAttacking(!config.aimAssistOnlyAttacking());
			b.setMessage(onlyLabel(config));
		}).width(ROW_WIDTH).build());

		// Humanisation, one knob per row. The preset slider at the top of the block
		// is only a shortcut that writes all of these at once — every value below is
		// editable on its own, and editing one makes the preset label read "Custom".
		NexoIntSlider[] knob = new NexoIntSlider[7];
		Button[] snap = new Button[1];
		Runnable refreshKnobs = () -> {
			knob[0].setIntValue(config.aimAssistReactionMin());
			knob[1].setIntValue(config.aimAssistReactionMax());
			knob[2].setIntValue(config.aimAssistRamp());
			knob[3].setIntValue(config.aimAssistNoise());
			knob[4].setIntValue(config.aimAssistOvershoot());
			knob[5].setIntValue(config.aimAssistSkip());
			knob[6].setIntValue(config.aimAssistSpeed());
			if (snap[0] != null) {
				snap[0].setMessage(snapLabel(config));
			}
		};

		NexoIntSlider stealth = new NexoIntSlider(0, 0, ROW_WIDTH, 20, "nexomod.qol.aimAssist.stealth",
				0, NexoConfig.AIM_ASSIST_STEALTH_STAGES - 1, config.aimAssistStealth(),
				stage -> {
					config.applyAimAssistStealthPreset(stage);
					refreshKnobs.run();
				},
				stage -> stealthLabel(config, stage));
		layout.addChild(stealth);

		knob[0] = knobSlider("nexomod.qol.aimAssist.reactionMin", 0, NexoConfig.AIM_ASSIST_REACTION_MAX_TICKS,
				config.aimAssistReactionMin(), config::setAimAssistReactionMin, stealth, config);
		knob[1] = knobSlider("nexomod.qol.aimAssist.reactionMax", 0, NexoConfig.AIM_ASSIST_REACTION_MAX_TICKS,
				config.aimAssistReactionMax(), config::setAimAssistReactionMax, stealth, config);
		knob[2] = knobSlider("nexomod.qol.aimAssist.ramp", 0, 100, config.aimAssistRamp(),
				config::setAimAssistRamp, stealth, config);
		knob[3] = knobSlider("nexomod.qol.aimAssist.noise", 0, 100, config.aimAssistNoise(),
				config::setAimAssistNoise, stealth, config);
		knob[4] = knobSlider("nexomod.qol.aimAssist.overshoot", 0, 100, config.aimAssistOvershoot(),
				config::setAimAssistOvershoot, stealth, config);
		knob[5] = knobSlider("nexomod.qol.aimAssist.skip", 0, 100, config.aimAssistSkip(),
				config::setAimAssistSkip, stealth, config);
		knob[6] = knobSlider("nexomod.qol.aimAssist.speed", NexoConfig.AIM_ASSIST_SPEED_MIN,
				NexoConfig.AIM_ASSIST_SPEED_MAX, config.aimAssistSpeed(), config::setAimAssistSpeed, stealth, config);
		for (NexoIntSlider slider : knob) {
			layout.addChild(slider);
		}

		snap[0] = Button.builder(snapLabel(config), b -> {
			config.setAimAssistSnap(!config.aimAssistSnap());
			b.setMessage(snapLabel(config));
			stealth.setMessage(stealthLabel(config, stealth.intValue()));
		}).width(ROW_WIDTH).build();
		layout.addChild(snap[0]);

		finishLayout();
	}

	/** One humanisation knob: a slider that also re-labels the preset above it. */
	private static NexoIntSlider knobSlider(String labelKey, int min, int max, int value, IntConsumer setter,
			NexoIntSlider stealth, NexoConfig config) {
		return new NexoIntSlider(0, 0, ROW_WIDTH, 20, labelKey, min, max, value, v -> {
			setter.accept(v);
			stealth.setMessage(stealthLabel(config, stealth.intValue()));
		});
	}

	private static Component stealthLabel(NexoConfig config, int stage) {
		String name = config.matchesAimAssistStealthPreset(stage) ? Integer.toString(stage) : "custom";
		return Component.translatable("nexomod.qol.aimAssist.stealth",
				Component.translatable("nexomod.qol.aimAssist.stealth." + name));
	}

	private static Component snapLabel(NexoConfig config) {
		return Component.translatable("nexomod.qol.aimAssist.snap",
				config.aimAssistSnap() ? CommonComponents.OPTION_ON : CommonComponents.OPTION_OFF);
	}

	private static Component targetsLabel(NexoConfig c) {
		return Component.translatable("nexomod.qol.aimAssist.targets",
				Component.translatable("nexomod.qol.aimAssist.targets." + c.aimAssistTargets()));
	}

	private static Component pointLabel(NexoConfig c) {
		return Component.translatable("nexomod.qol.aimAssist.point",
				Component.translatable("nexomod.qol.aimAssist.point." + c.aimAssistPoint()));
	}

	private static Component onlyLabel(NexoConfig c) {
		return Component.translatable("nexomod.qol.aimAssist.onlyAttacking", c.aimAssistOnlyAttacking() ? CommonComponents.OPTION_ON : CommonComponents.OPTION_OFF);
	}
}
