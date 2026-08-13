package dev.nexoclient.nexomod.full.screen;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

import dev.nexoclient.nexomod.full.macro.NexoMacroTrigger;
import dev.nexoclient.nexomod.full.macro.NexoMacroTriggerConfig;
import dev.nexoclient.nexomod.macro.NexoMacro;
import dev.nexoclient.nexomod.macro.NexoMacroConfig;
import dev.nexoclient.nexomod.screen.NexoIntSlider;
import dev.nexoclient.nexomod.screen.NexoOptionList;

/**
 * Two rows per rule: what fires it, and what it does.
 *
 * <p>The macro is chosen from the existing macro list rather than written here,
 * so the "what it does" half is the one the player already edits under Macros
 * and there is one place a chat line is defined. A rule with no macro selected
 * is still useful — {@code TOOL_LOW} with the hotbar swap on and no macro is the
 * whole feature for most people.
 */
public class NexoMacroTriggerOptionList extends NexoOptionList {
	/** The sentinel {@code macroId} for "don't run a macro". */
	private static final String NO_MACRO = "";

	public NexoMacroTriggerOptionList(Minecraft mc, int width, int height, int y, int entryWidth, int entryHeight, int entrySpacing) {
		super(mc, width, height, y, entryWidth, entryHeight, entrySpacing);
	}

	@Override
	protected void addEntries() {
		addEntry(new NexoOptionList.Entry.Text(dynEntryX, dynEntryWidth, entryHeight,
				Component.translatable("nexomod.macroTriggers.help").withStyle(ChatFormatting.GRAY), null, -1));

		List<NexoMacroTrigger> triggers = NexoMacroTriggerConfig.get().triggers();
		if (triggers.isEmpty()) {
			addEntry(new NexoOptionList.Entry.Text(dynEntryX, dynEntryWidth, entryHeight,
					Component.translatable("nexomod.macroTriggers.none"), null, -1));
		}
		for (NexoMacroTrigger trigger : triggers) {
			addEntry(new ConditionRow(dynEntryX, dynEntryWidth, entryHeight, this, trigger));
			addEntry(new ActionRow(dynEntryX, dynEntryWidth, entryHeight, trigger));
		}
		addEntry(new NexoOptionList.Entry.ActionButton(dynEntryX, dynEntryWidth, entryHeight,
				Component.literal("+"), null, -1, button -> {
					NexoMacroTriggerConfig.get().add(new NexoMacroTrigger());
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

	/** Condition, its threshold, on/off and delete. */
	private static class ConditionRow extends NexoOptionList.Entry {
		ConditionRow(int x, int width, int height, NexoMacroTriggerOptionList list, NexoMacroTrigger trigger) {
			int small = list.smallWidgetWidth;
			int toggleWidth = 46;
			int half = Math.max(60, (width - toggleWidth - small - (SPACE_SMALL * 3)) / 2);

			int cursor = x;
			elements.add(CycleButton.<NexoMacroTrigger.Condition>builder(NexoMacroTriggerOptionList::conditionLabel, trigger.condition)
					.withValues(NexoMacroTrigger.Condition.values())
					.withTooltip(value -> Tooltip.create(Component.translatable("nexomod.macroTriggers.condition.tooltip")))
					.create(cursor, 0, half, height, Component.empty(), (button, value) -> {
						trigger.condition = value;
						// The threshold's units and range depend on the
						// condition, so the slider next to it has to be rebuilt
						// rather than relabelled.
						list.init();
					}));
			cursor += half + SPACE_SMALL;

			if (trigger.condition == NexoMacroTrigger.Condition.INVENTORY_FULL) {
				// No threshold to set; the cooldown is the only number that
				// still means anything for this condition.
				elements.add(new NexoIntSlider(cursor, 0, half, height, "nexomod.macroTriggers.cooldown",
						0, 300, trigger.cooldownSeconds, value -> trigger.cooldownSeconds = value));
			} else {
				int max = trigger.condition == NexoMacroTrigger.Condition.HUNGER_LOW ? 20 : 100;
				trigger.threshold = Math.clamp(trigger.threshold, 0, max);
				elements.add(new NexoIntSlider(cursor, 0, half, height, thresholdKey(trigger.condition),
						0, max, trigger.threshold, value -> trigger.threshold = value));
			}
			cursor += half + SPACE_SMALL;

			elements.add(CycleButton.onOffBuilder(trigger.enabled)
					.displayOnlyValue()
					.create(cursor, 0, toggleWidth, height, Component.translatable("nexomod.macroTriggers.enabled"),
							(button, value) -> trigger.enabled = value));
			cursor += toggleWidth + SPACE_SMALL;

			elements.add(Button.builder(Component.literal("❌").withStyle(ChatFormatting.RED), button -> {
						NexoMacroTriggerConfig.get().remove(trigger);
						list.init();
					})
					.pos(cursor, 0).size(small, height).build());
		}
	}

	/** Which macro runs, and whether a worn-out tool also swaps. */
	private static class ActionRow extends NexoOptionList.Entry {
		ActionRow(int x, int width, int height, NexoMacroTrigger trigger) {
			int half = width / 2;

			List<String> ids = new ArrayList<>();
			ids.add(NO_MACRO);
			for (NexoMacro macro : NexoMacroConfig.get().macros()) {
				ids.add(macro.id);
			}
			String current = ids.contains(trigger.macroId) ? trigger.macroId : NO_MACRO;

			elements.add(CycleButton.<String>builder(NexoMacroTriggerOptionList::macroLabel, current)
					.withValues(ids)
					.withTooltip(value -> Tooltip.create(Component.translatable("nexomod.macroTriggers.macro.tooltip")))
					.create(x, 0, half - SPACE_SMALL, height, Component.empty(),
							(button, value) -> trigger.macroId = value));

			CycleButton<Boolean> swap = CycleButton.onOffBuilder(trigger.swapTool)
					.withTooltip(value -> Tooltip.create(Component.translatable("nexomod.macroTriggers.swap.tooltip")))
					.create(x + half, 0, width - half, height,
							Component.translatable("nexomod.macroTriggers.swap"),
							(button, value) -> trigger.swapTool = value);
			// Only TOOL_LOW has anything to swap; leaving the button live for
			// the others would offer a setting that silently does nothing.
			swap.active = trigger.condition == NexoMacroTrigger.Condition.TOOL_LOW;
			elements.add(swap);
		}
	}

	private static String thresholdKey(NexoMacroTrigger.Condition condition) {
		return condition == NexoMacroTrigger.Condition.HUNGER_LOW
				? "nexomod.macroTriggers.threshold.points"
				: "nexomod.macroTriggers.threshold.percent";
	}

	private static Component conditionLabel(NexoMacroTrigger.Condition condition) {
		return Component.translatable("nexomod.macroTriggers.condition." + condition.name().toLowerCase(Locale.ROOT));
	}

	private static Component macroLabel(String id) {
		if (id == null || id.isEmpty()) {
			return Component.translatable("nexomod.macroTriggers.macro.none");
		}
		for (NexoMacro macro : NexoMacroConfig.get().macros()) {
			if (id.equals(macro.id)) {
				return Component.literal(macro.name);
			}
		}
		// The macro was deleted after the rule was made. Named rather than
		// silently reset, so the rule can be repointed instead of looking as if
		// it never had a macro.
		return Component.translatable("nexomod.macroTriggers.macro.missing").withStyle(ChatFormatting.RED);
	}
}
