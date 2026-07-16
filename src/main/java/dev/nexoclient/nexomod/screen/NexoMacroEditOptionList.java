package dev.nexoclient.nexomod.screen;

import java.util.Locale;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.network.chat.Component;

import dev.nexoclient.nexomod.macro.NexoMacro;

/** Field list for editing one {@link NexoMacro}, modeled on CommandKeys' {@code MacroOptionList}. */
public class NexoMacroEditOptionList extends NexoOptionList {
	private final NexoMacro macro;
	private boolean capturing;

	public NexoMacroEditOptionList(Minecraft mc, int width, int height, int y, int entryWidth, int entryHeight, int entrySpacing, NexoMacro macro) {
		super(mc, width, height, y, entryWidth, entryHeight, entrySpacing);
		this.macro = macro;
		if (macro.commands.isEmpty()) {
			macro.commands.add("");
		}
	}

	@Override
	protected void addEntries() {
		addEntry(new Entry.NameField(dynEntryX, dynEntryWidth, entryHeight, macro));
		addEntry(new Entry.KeybindRow(dynEntryX, dynEntryWidth, entryHeight, this, macro));
		addEntry(new Entry.ModeRow(dynEntryX, dynEntryWidth, entryHeight, this, macro));
		if (macro.mode == NexoMacro.Mode.SEND || macro.mode == NexoMacro.Mode.REPEAT) {
			addEntry(new Entry.DelayField(dynEntryX, dynEntryWidth, entryHeight,
					Component.translatable("nexomod.macros.delay"), macro.delayTicks, val -> macro.delayTicks = val));
		}
		if (macro.mode == NexoMacro.Mode.REPEAT) {
			addEntry(new Entry.DelayField(dynEntryX, dynEntryWidth, entryHeight,
					Component.translatable("nexomod.macros.repeatInterval"), macro.repeatIntervalTicks, val -> macro.repeatIntervalTicks = Math.max(1, val)));
		}

		addEntry(new NexoOptionList.Entry.Text(dynEntryX, dynEntryWidth, entryHeight,
				Component.translatable("nexomod.macros.commandsLabel"), null, -1));

		for (int i = 0; i < macro.commands.size(); i++) {
			addEntry(new Entry.CommandField(dynEntryX, dynEntryWidth, entryHeight, this, macro, i));
		}

		addEntry(new NexoOptionList.Entry.ActionButton(dynEntryX, dynEntryWidth, entryHeight,
				Component.literal("+"), null, -1, button -> {
					macro.commands.add("");
					init();
				}));
	}

	@Override
	public boolean keyPressed(InputConstants.Key key) {
		if (capturing) {
			capturing = false;
			if (key.getValue() != InputConstants.KEY_ESCAPE) {
				macro.keyCode = key.getValue();
			}
			init();
			return true;
		}
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
		private static class NameField extends Entry {
			NameField(int x, int width, int height, NexoMacro macro) {
				int labelWidth = 70;
				Button label = Button.builder(Component.translatable("nexomod.macros.name"), button -> {}).pos(x, 0).size(labelWidth, height).build();
				label.active = false;
				elements.add(label);

				NexoTextField nameField = new NexoTextField(x + labelWidth, 0, width - labelWidth, height);
				nameField.setMaxLength(48);
				nameField.setValue(macro.name);
				nameField.setResponder(val -> macro.name = val.strip());
				elements.add(nameField);
			}
		}

		private static class KeybindRow extends Entry {
			KeybindRow(int x, int width, int height, NexoMacroEditOptionList list, NexoMacro macro) {
				int modifierWidth = (width - SPACE) / 3;
				int captureWidth = width - modifierWidth - SPACE;

				elements.add(Button.builder(keyLabel(macro), button -> {
					list.capturing = true;
					button.setMessage(Component.translatable("nexomod.macros.listening"));
				}).pos(x, 0).size(captureWidth, height).build());

				elements.add(CycleButton.builder(NexoMacroEditOptionList::modifierLabel, macro.modifier)
						.withValues(NexoMacro.Modifier.values())
						.create(x + captureWidth + SPACE, 0, modifierWidth, height, Component.translatable("nexomod.macros.modifierLabel"),
								(button, status) -> macro.modifier = status));
			}

			private static Component keyLabel(NexoMacro macro) {
				return macro.keyCode >= 0
						? InputConstants.Type.KEYSYM.getOrCreate(macro.keyCode).getDisplayName()
						: Component.translatable("nexomod.macros.unbound");
			}
		}

		private static class ModeRow extends Entry {
			ModeRow(int x, int width, int height, NexoMacroEditOptionList list, NexoMacro macro) {
				elements.add(CycleButton.builder(NexoMacroEditOptionList::modeLabel, macro.mode)
						.withValues(NexoMacro.Mode.values())
						.create(x, 0, width, height, Component.translatable("nexomod.macros.modeLabel"),
								(button, status) -> {
									macro.mode = status;
									list.init();
								}));
			}
		}

		private static class DelayField extends Entry {
			DelayField(int x, int width, int height, Component labelText, int value, java.util.function.IntConsumer setter) {
				int labelWidth = 140;
				Button label = Button.builder(labelText, button -> {}).pos(x, 0).size(labelWidth, height).build();
				label.active = false;
				elements.add(label);

				NexoTextField field = new NexoTextField(x + labelWidth, 0, width - labelWidth, height);
				field.posIntValidator();
				field.setMaxLength(6);
				field.setValue(String.valueOf(value));
				field.setResponder(val -> {
					try {
						setter.accept(Math.max(0, Integer.parseInt(val.trim())));
					} catch (NumberFormatException ignored) {
						// Leave the stored value unchanged; the field is already flagged red.
					}
				});
				elements.add(field);
			}
		}

		private static class CommandField extends Entry {
			CommandField(int x, int width, int height, NexoMacroEditOptionList list, NexoMacro macro, int index) {
				int deleteWidth = list.smallWidgetWidth;
				int fieldWidth = width - deleteWidth - SPACE;

				NexoTextField commandField = new NexoTextField(x, 0, fieldWidth, height);
				commandField.setMaxLength(256);
				commandField.setValue(macro.commands.get(index));
				commandField.setHint(Component.translatable("nexomod.macros.command"));
				commandField.setResponder(val -> macro.commands.set(index, val));
				elements.add(commandField);

				Button deleteButton = Button.builder(Component.literal("❌").withStyle(ChatFormatting.RED), button -> {
					if (macro.commands.size() > 1) {
						macro.commands.remove(index);
						list.init();
					}
				}).pos(x + fieldWidth + SPACE, 0).size(deleteWidth, height).build();
				deleteButton.active = macro.commands.size() > 1;
				elements.add(deleteButton);
			}
		}
	}

	private static Component modifierLabel(NexoMacro.Modifier modifier) {
		String key = switch (modifier) {
			case NONE -> "nexomod.macros.modifier.none";
			case SHIFT -> "nexomod.macros.modifier.shift";
			case CTRL -> "nexomod.macros.modifier.ctrl";
			case ALT -> "nexomod.macros.modifier.alt";
		};
		return Component.translatable(key);
	}

	private static Component modeLabel(NexoMacro.Mode mode) {
		return Component.translatable("nexomod.macros.mode." + mode.name().toLowerCase(Locale.ROOT));
	}
}
