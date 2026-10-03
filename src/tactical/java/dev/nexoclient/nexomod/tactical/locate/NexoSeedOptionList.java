package dev.nexoclient.nexomod.tactical.locate;

import java.util.List;
import java.util.OptionalLong;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

import dev.nexoclient.nexomod.screen.NexoOptionList;
import dev.nexoclient.nexomod.screen.NexoTextField;

/** One row per saved place under "Servers" and "Worlds" headings; the seed field saves as you type. */
class NexoSeedOptionList extends NexoOptionList {
	NexoSeedOptionList(Minecraft mc, int width, int height, int y, int entryWidth, int entryHeight, int entrySpacing) {
		super(mc, width, height, y, entryWidth, entryHeight, entrySpacing);
	}

	@Override
	protected void addEntries() {
		List<SeedIndex.Entry> all = SeedIndex.get().sorted();
		if (all.isEmpty()) {
			addEntry(new Entry.Text(dynWideEntryX, dynWideEntryWidth, entryHeight,
					Component.translatable("nexomod.seedIndex.none"), null, -1));
			return;
		}
		boolean serversHeader = false;
		boolean worldsHeader = false;
		for (SeedIndex.Entry entry : all) {
			if (!entry.world && !serversHeader) {
				serversHeader = true;
				addEntry(new Entry.Text(dynWideEntryX, dynWideEntryWidth, entryHeight,
						Component.translatable("nexomod.seedIndex.servers").withStyle(ChatFormatting.BOLD), null, -1));
			}
			if (entry.world && !worldsHeader) {
				worldsHeader = true;
				addEntry(new Entry.Text(dynWideEntryX, dynWideEntryWidth, entryHeight,
						Component.translatable("nexomod.seedIndex.worlds").withStyle(ChatFormatting.BOLD), null, -1));
			}
			addEntry(new Row(dynWideEntryX, dynWideEntryWidth, entryHeight, this, entry));
		}
	}

	// Typing in a seed field must not trigger the list's own key handling.
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

	private static class Row extends NexoOptionList.Entry {
		Row(int x, int width, int height, NexoSeedOptionList list, SeedIndex.Entry entry) {
			int small = list.smallWidgetWidth;
			int seedWidth = Math.max(90, width * 2 / 5);
			int versionWidth = 52;
			int nameWidth = width - seedWidth - versionWidth - small - SPACE_SMALL * 3;

			// Address under the name would need a taller row; the tooltip carries it.
			StringWidget name = new StringWidget(x, 0, nameWidth, height,
					Component.literal(entry.label == null ? entry.key : entry.label), Minecraft.getInstance().font);
			Component origin = Component.translatable("nexomod.seedIndex.source."
					+ SeedIndex.Source.of(entry.source).name().toLowerCase(java.util.Locale.ROOT));
			name.setTooltip(Tooltip.create(entry.address.isEmpty() ? origin
					: Component.literal(entry.address).append("\n").append(origin)));
			elements.add(name);

			NexoTextField seed = new NexoTextField(x + nameWidth + SPACE_SMALL, 0, seedWidth, height);
			seed.setMaxLength(32);
			seed.setValue(Long.toString(entry.seed));
			seed.setResponder(value -> {
				OptionalLong parsed = SeedIndex.tryParseSeed(value);
				if (parsed.isPresent()) {
					SeedIndex.get().setSeed(entry, parsed.getAsLong());
				}
			});
			elements.add(seed);

			elements.add(Button.builder(Component.literal("❌").withStyle(ChatFormatting.RED), button -> {
						SeedIndex.get().remove(entry);
						list.init();
					})
					.pos(x + width - small, 0).size(small, height).build());
		}
	}
}
