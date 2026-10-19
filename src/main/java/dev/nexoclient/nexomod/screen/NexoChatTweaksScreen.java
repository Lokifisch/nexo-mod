package dev.nexoclient.nexomod.screen;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import dev.nexoclient.nexomod.chat.NexoChatTweaks;

/** Chat tweaks plus the second chat's routing prefixes. The master toggle is the second chat. */
public class NexoChatTweaksScreen extends NexoModalScreen {
	private static final int ROW_WIDTH = 260;
	private static final List<Integer> HISTORY = List.of(100, 200, 500, 1000, 2000);
	private static final List<Integer> PANEL_LINES = List.of(4, 6, 8, 10, 12, 15, 20);

	public NexoChatTweaksScreen(Screen parent) {
		super(Component.translatable("nexomod.qol.chat"), parent);
	}

	@Override
	protected void init() {
		super.init();
		layout.defaultCellSetting().alignHorizontallyCenter();
		layout.addChild(new StringWidget(title.copy().withStyle(style -> style.withColor(NexoStyle.TEXT_ACTIVE_ACCENT).withBold(true)), font));

		layout.addChild(new NexoModuleRow(0, 0, ROW_WIDTH, 36,
				Component.translatable("nexomod.chat.second"),
				Component.translatable("nexomod.qol.chat.description"),
				() -> NexoChatTweaks.secondEnabled,
				() -> {
					NexoChatTweaks.secondEnabled = !NexoChatTweaks.secondEnabled;
					NexoChatTweaks.save();
					minecraft.gui.setScreen(new NexoChatTweaksScreen(parent));
				}));

		NexoTextField prefixField = new NexoTextField(0, 0, ROW_WIDTH, 20);
		prefixField.setMaxLength(256);
		prefixField.setHint(Component.translatable("nexomod.chat.prefixes.hint"));
		prefixField.setValue(NexoChatTweaks.prefixes);
		prefixField.moveCursorToEnd(false);
		prefixField.setResponder(value -> {
			NexoChatTweaks.prefixes = value;
			NexoChatTweaks.save();
		});
		layout.addChild(prefixField);

		toggle("keepCopy", () -> NexoChatTweaks.keepCopy, v -> NexoChatTweaks.keepCopy = v);
		cycle("secondLines", PANEL_LINES, NexoChatTweaks.secondLines, v -> NexoChatTweaks.secondLines = v);
		layout.addChild(Button.builder(Component.translatable("nexomod.qol.editLayout"),
						button -> minecraft.gui.setScreen(new NexoHudEditorScreen(this)))
				.size(ROW_WIDTH, 20).build());

		toggle("timestamps", () -> NexoChatTweaks.timestamps, v -> NexoChatTweaks.timestamps = v);
		toggle("compact", () -> NexoChatTweaks.compact, v -> NexoChatTweaks.compact = v);
		cycle("maxLines", HISTORY, NexoChatTweaks.maxLines, v -> NexoChatTweaks.maxLines = v);

		finishLayout();
	}

	private void toggle(String key, BooleanSupplier getter, Consumer<Boolean> setter) {
		layout.addChild(CycleButton.onOffBuilder(getter.getAsBoolean())
				.create(0, 0, ROW_WIDTH, 20, Component.translatable("nexomod.chat." + key),
						(button, value) -> {
							setter.accept(value);
							NexoChatTweaks.save();
						}));
	}

	private void cycle(String key, List<Integer> values, int current, Consumer<Integer> setter) {
		Integer initial = values.contains(current) ? current : values.get(0);
		layout.addChild(CycleButton.<Integer>builder(v -> Component.literal(Integer.toString(v)), initial)
				.withValues(values)
				.create(0, 0, ROW_WIDTH, 20, Component.translatable("nexomod.chat." + key),
						(button, value) -> {
							setter.accept(value);
							NexoChatTweaks.save();
						}));
	}
}
