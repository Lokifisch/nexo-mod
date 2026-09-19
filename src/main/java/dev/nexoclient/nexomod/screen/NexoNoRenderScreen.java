package dev.nexoclient.nexomod.screen;

import java.util.Comparator;
import java.util.List;

import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;

import dev.nexoclient.nexomod.norender.NexoNoRender;
import dev.nexoclient.nexomod.norender.NexoNoRenderConfig;

/**
 * The No Render entity picker — see {@code NexoNoRender} for the actual
 * render/hitbox skip and the Dynamic auto-detection this screen only
 * configures.
 *
 * <p>Every registered {@link EntityType} is listed (not just ones currently
 * loaded), same reasoning as the stats screen listing every stat rather than
 * only ones seen this session — a type can be pre-hidden before ever running
 * into one. At 100+ entries this is exactly the list the search field
 * exists for.
 */
public class NexoNoRenderScreen extends NexoModalScreen {
	private static final int ROW_WIDTH = 220;

	/** Survives across {@code init()} rebuilds — a fresh {@link NexoTextField} would forget it otherwise. */
	private String query = "";
	private NexoTextField searchField;

	public NexoNoRenderScreen(Screen parent) {
		super(Component.translatable("nexomod.qol.noRender"), parent);
	}

	@Override
	protected void init() {
		super.init();
		layout.defaultCellSetting().alignHorizontallyCenter();
		layout.addChild(new StringWidget(title.copy().withStyle(style -> style.withColor(NexoStyle.TEXT_ACTIVE_ACCENT).withBold(true)), font));

		NexoNoRenderConfig config = NexoNoRenderConfig.get();
		layout.addChild(new NexoModuleRow(0, 0, ROW_WIDTH, 36,
				Component.translatable("nexomod.qol.noRender.dynamic"),
				Component.translatable("nexomod.qol.noRender.dynamic.description"),
				config::dynamicEnabled,
				() -> NexoNoRender.setDynamicEnabled(!config.dynamicEnabled())));
		layout.addChild(new NexoIntSlider(0, 0, ROW_WIDTH, 20, "nexomod.qol.noRender.threshold",
				NexoNoRenderConfig.DYNAMIC_FPS_THRESHOLD_MIN, NexoNoRenderConfig.DYNAMIC_FPS_THRESHOLD_MAX,
				config.dynamicFpsThreshold(), config::setDynamicFpsThreshold));

		searchField = new NexoTextField(0, 0, ROW_WIDTH, 20);
		searchField.setHint(Component.translatable("nexomod.qol.noRender.search"));
		searchField.setValue(query);
		searchField.moveCursorToEnd(false);
		searchField.setResponder(value -> {
			query = value;
			rebuildWidgets();
		});
		layout.addChild(searchField);
		setInitialFocus(searchField);

		String needle = query.toLowerCase();
		for (EntityType<?> type : sortedEntityTypes()) {
			Component label = Component.translatable(EntityType.getKey(type).toLanguageKey("entity"));
			if (!needle.isEmpty() && !label.getString().toLowerCase().contains(needle)) {
				continue;
			}
			String path = EntityType.getKey(type).getPath();
			if (NexoNoRender.dynamicallyHidden().contains(type) && !config.isManuallyHidden(path)) {
				label = label.copy().append(Component.translatable("nexomod.qol.noRender.autoHidden"));
			}
			layout.addChild(CycleButton.onOffBuilder(config.isManuallyHidden(path))
					.create(0, 0, ROW_WIDTH, 20, label,
							(button, value) -> config.setManuallyHidden(path, value)));
		}

		finishLayout();
	}

	private static List<EntityType<?>> sortedEntityTypes() {
		return BuiltInRegistries.ENTITY_TYPE.stream()
				.sorted(Comparator.comparing(type -> Component.translatable(EntityType.getKey(type).toLanguageKey("entity")).getString()))
				.toList();
	}
}
