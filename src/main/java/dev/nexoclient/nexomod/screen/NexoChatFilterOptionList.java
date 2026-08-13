package dev.nexoclient.nexomod.screen;

import java.util.List;
import java.util.Map;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

import dev.nexoclient.nexomod.chat.NexoChatFilter;
import dev.nexoclient.nexomod.chat.NexoChatPattern;
import dev.nexoclient.nexomod.nativecore.NexoNative;

/**
 * One row per rule: the regex, what it does, whether it is on, and a delete
 * button.
 *
 * <p>Compile errors from the last rebuild are shown as a red line under the rule
 * they belong to — {@link NexoChatFilter#errors()} is keyed by index in the
 * pattern list for exactly that, since a "your regex is invalid" message with no
 * indication of which one is not actionable when there are six rules.
 */
public class NexoChatFilterOptionList extends NexoOptionList {
	public NexoChatFilterOptionList(Minecraft mc, int width, int height, int y, int entryWidth, int entryHeight, int entrySpacing) {
		super(mc, width, height, y, entryWidth, entryHeight, entrySpacing);
	}

	@Override
	protected void addEntries() {
		NexoChatFilter filter = NexoChatFilter.get();
		List<NexoChatPattern> patterns = filter.patterns();
		Map<Integer, String> errors = filter.errors();

		addEntry(new NexoOptionList.Entry.Text(dynEntryX, dynEntryWidth, entryHeight,
				Component.translatable("nexomod.chat.filters.help").withStyle(ChatFormatting.GRAY), null, -1));

		if (patterns.isEmpty()) {
			addEntry(new NexoOptionList.Entry.Text(dynEntryX, dynEntryWidth, entryHeight,
					Component.translatable("nexomod.chat.filters.none"), null, -1));
		}

		for (int i = 0; i < patterns.size(); i++) {
			NexoChatPattern pattern = patterns.get(i);
			addEntry(new PatternRow(dynEntryX, dynEntryWidth, entryHeight, this, filter, pattern));
			String error = errors.get(i);
			if (error != null) {
				addEntry(new NexoOptionList.Entry.Text(dynEntryX, dynEntryWidth, entryHeight,
						Component.translatable("nexomod.chat.filters.badRegex", error).withStyle(ChatFormatting.RED),
						null, -1));
			}
		}

		addEntry(new NexoOptionList.Entry.ActionButton(dynEntryX, dynEntryWidth, entryHeight,
				Component.literal("+"), null, -1, button -> {
					filter.add(new NexoChatPattern());
					init();
				}));
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

	private static class PatternRow extends NexoOptionList.Entry {
		PatternRow(int x, int width, int height, NexoChatFilterOptionList list, NexoChatFilter filter, NexoChatPattern pattern) {
			int small = list.smallWidgetWidth;
			int actionWidth = 76;
			int toggleWidth = 46;
			int regexWidth = Math.max(60, width - actionWidth - toggleWidth - small - (SPACE_SMALL * 3));

			int cursor = x;
			NexoTextField regex = new NexoTextField(cursor, 0, regexWidth, height);
			regex.setMaxLength(256);
			regex.setValue(pattern.regex == null ? "" : pattern.regex);
			regex.setHint(Component.translatable("nexomod.chat.filters.regexHint"));
			regex.setResponder(value -> pattern.regex = value);
			elements.add(regex);
			cursor += regexWidth + SPACE_SMALL;

			// A plain Button rather than a CycleButton: the action has exactly
			// two useful values (ALLOW is indistinguishable from having no rule
			// at all) and NexoChatPattern.cycleAction() already encodes that.
			Button action = Button.builder(actionLabel(pattern), button -> {
						pattern.cycleAction();
						button.setMessage(actionLabel(pattern));
					})
					.pos(cursor, 0).size(actionWidth, height).build();
			action.setTooltip(Tooltip.create(Component.translatable("nexomod.chat.filters.action.tooltip")));
			elements.add(action);
			cursor += actionWidth + SPACE_SMALL;

			elements.add(CycleButton.onOffBuilder(pattern.enabled)
					.displayOnlyValue()
					.create(cursor, 0, toggleWidth, height, Component.translatable("nexomod.chat.filters.enabled"),
							(button, value) -> pattern.enabled = value));
			cursor += toggleWidth + SPACE_SMALL;

			elements.add(Button.builder(Component.literal("❌").withStyle(ChatFormatting.RED), button -> {
						filter.patterns().remove(pattern);
						filter.save();
						list.init();
					})
					.pos(cursor, 0).size(small, height).build());
		}

		private static Component actionLabel(NexoChatPattern pattern) {
			return pattern.action == NexoNative.FILTER_HIGHLIGHT
					? Component.translatable("nexomod.chat.filters.action.highlight").withStyle(ChatFormatting.YELLOW)
					: Component.translatable("nexomod.chat.filters.action.hide").withStyle(ChatFormatting.GRAY);
		}
	}
}
