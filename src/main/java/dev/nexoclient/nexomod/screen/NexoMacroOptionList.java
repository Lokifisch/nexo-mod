package dev.nexoclient.nexomod.screen;

import java.util.List;
import java.util.Locale;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.network.chat.Component;

import dev.nexoclient.nexomod.macro.NexoMacro;
import dev.nexoclient.nexomod.macro.NexoMacroConfig;

/** Row-per-macro list for {@link NexoMacroListScreen}, modeled on CommandKeys' {@code MainOptionList} profile rows. */
public class NexoMacroOptionList extends NexoOptionList {
	public NexoMacroOptionList(Minecraft mc, int width, int height, int y, int entryWidth, int entryHeight, int entrySpacing) {
		super(mc, width, height, y, entryWidth, entryHeight, entrySpacing);
	}

	@Override
	protected void addEntries() {
		List<NexoMacro> macros = NexoMacroConfig.get().macros();
		if (macros.isEmpty()) {
			addEntry(new NexoOptionList.Entry.Text(dynEntryX, dynEntryWidth, entryHeight,
					Component.translatable("nexomod.macros.none"), null, -1));
		}
		for (NexoMacro macro : macros) {
			addEntry(new Entry.MacroRow(dynEntryX, dynEntryWidth, entryHeight, this, macro));
		}
		addEntry(new NexoOptionList.Entry.ActionButton(dynEntryX, dynEntryWidth, entryHeight,
				Component.literal("+"), null, -1, button -> {
					NexoMacro macro = new NexoMacro();
					NexoMacroConfig.get().addMacro(macro);
					openMacroEditor(macro);
				}));
	}

	private void openMacroEditor(NexoMacro macro) {
		mc.setScreen(new NexoMacroEditScreen(screen, macro));
	}

	@Override
	public boolean keyPressed(InputConstants.Key key) {
		return false;
	}

	@Override
	public boolean keyReleased(InputConstants.Key key) {
		return false;
	}

	@Override
	public boolean mouseClicked(InputConstants.Key key) {
		return false;
	}

	@Override
	public boolean mouseReleased(InputConstants.Key key) {
		return false;
	}

	private abstract static class Entry extends NexoOptionList.Entry {
		private static class MacroRow extends Entry {
			MacroRow(int x, int width, int height, NexoMacroOptionList list, NexoMacro macro) {
				int mainButtonWidth = width - list.smallWidgetWidth - SPACE_SMALL;

				elements.add(Button.builder(rowLabel(macro), button -> list.openMacroEditor(macro))
						.pos(x, 0).size(mainButtonWidth, height).build());

				Button deleteButton = Button.builder(Component.literal("❌").withStyle(ChatFormatting.RED), button ->
						Minecraft.getInstance().setScreen(new ConfirmScreen(
								confirmed -> {
									if (confirmed) {
										NexoMacroConfig.get().removeMacro(macro);
									}
									Minecraft.getInstance().setScreen(list.screen);
									list.init();
								},
								Component.translatable("nexomod.macros.deleteTitle"),
								Component.translatable("nexomod.macros.deleteConfirm", macro.name))))
						.pos(x + width - list.smallWidgetWidth, 0).size(list.smallWidgetWidth, height).build();
				elements.add(deleteButton);
			}
		}

		private static Component rowLabel(NexoMacro macro) {
			Component key = macro.keyCode >= 0
					? InputConstants.Type.KEYSYM.getOrCreate(macro.keyCode).getDisplayName()
					: Component.translatable("nexomod.macros.unbound");
			Component modifier = switch (macro.modifier) {
				case NONE -> Component.empty();
				case SHIFT -> Component.literal("Shift+");
				case CTRL -> Component.literal("Ctrl+");
				case ALT -> Component.literal("Alt+");
			};
			Component mode = Component.translatable("nexomod.macros.mode." + macro.mode.name().toLowerCase(Locale.ROOT));
			return Component.literal(macro.name + "  ")
					.append(Component.literal("[").withStyle(ChatFormatting.GRAY))
					.append(modifier).append(key)
					.append(Component.literal(" · ")).append(mode)
					.append(Component.literal("]").withStyle(ChatFormatting.GRAY));
		}
	}
}
