package dev.nexoclient.nexomod.screen;

import java.util.List;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

import dev.nexoclient.nexomod.servers.NexoQuickConnect;
import dev.nexoclient.nexomod.servers.NexoServerEntry;
import dev.nexoclient.nexomod.servers.NexoServerList;

/**
 * Row-per-favourite list for {@link NexoQuickServerScreen}.
 *
 * <p>Editing is inline — a name field, an address field and a Join button on
 * one row — rather than a second screen per entry the way macros do it. A macro
 * has a key, a modifier, a mode, a delay and a list of commands; a favourite has
 * two strings, and a screen that exists to edit two strings is one more click in
 * both directions for nothing.
 *
 * <p>Text edits land in the live {@link NexoServerEntry} object as they are
 * typed and are written to disk when the screen closes, which is the same
 * arrangement {@code NexoMacroListScreen} uses.
 */
public class NexoQuickServerOptionList extends NexoOptionList {
	public NexoQuickServerOptionList(Minecraft mc, int width, int height, int y, int entryWidth, int entryHeight, int entrySpacing) {
		super(mc, width, height, y, entryWidth, entryHeight, entrySpacing);
	}

	@Override
	protected void addEntries() {
		List<NexoServerEntry> servers = NexoServerList.get().entries();
		if (servers.isEmpty()) {
			addEntry(new NexoOptionList.Entry.Text(dynEntryX, dynEntryWidth, entryHeight,
					Component.translatable("nexomod.servers.none"), null, -1));
		}
		for (NexoServerEntry entry : servers) {
			addEntry(new ServerRow(dynEntryX, dynEntryWidth, entryHeight, this, entry));
		}
		addEntry(new NexoOptionList.Entry.ActionButton(dynEntryX, dynEntryWidth, entryHeight,
				Component.literal("+"), null, -1, button -> {
					NexoServerList.get().add(new NexoServerEntry());
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

	private static class ServerRow extends NexoOptionList.Entry {
		ServerRow(int x, int width, int height, NexoQuickServerOptionList list, NexoServerEntry entry) {
			int small = list.smallWidgetWidth;
			int joinWidth = 44;
			int fields = width - joinWidth - (small * 2) - (SPACE_SMALL * 3);
			int nameWidth = Math.max(40, fields * 2 / 5);
			int addressWidth = Math.max(40, fields - nameWidth);

			int cursor = x;
			NexoTextField name = new NexoTextField(cursor, 0, nameWidth, height);
			name.setMaxLength(64);
			name.setValue(entry.name == null ? "" : entry.name);
			name.setHint(Component.translatable("nexomod.servers.nameHint"));
			name.setResponder(value -> entry.name = value);
			elements.add(name);
			cursor += nameWidth + SPACE_SMALL;

			NexoTextField address = new NexoTextField(cursor, 0, addressWidth, height);
			address.setMaxLength(128);
			address.setValue(entry.address == null ? "" : entry.address);
			address.setHint(Component.translatable("nexomod.servers.addressHint"));
			address.setResponder(value -> entry.address = value);
			elements.add(address);
			cursor += addressWidth + SPACE_SMALL;

			// Saved before joining, not after: startConnecting replaces the
			// screen, so this screen's onClose never runs on the join path and
			// an address typed one keystroke ago would be lost.
			Button join = Button.builder(Component.translatable("nexomod.servers.join"), button -> {
						NexoServerList.get().save();
						NexoQuickConnect.switchTo(entry);
					})
					.pos(cursor, 0).size(joinWidth, height).build();
			join.setTooltip(Tooltip.create(Component.translatable("nexomod.servers.join.tooltip")));
			elements.add(join);
			cursor += joinWidth + SPACE_SMALL;

			elements.add(Button.builder(Component.literal("▲"), button -> {
						NexoServerList.get().moveUp(entry);
						list.init();
					})
					.pos(cursor, 0).size(small, height).build());
			cursor += small;

			elements.add(Button.builder(Component.literal("❌").withStyle(ChatFormatting.RED), button -> {
						NexoServerList.get().remove(entry);
						list.init();
					})
					.pos(cursor, 0).size(small, height).build());
		}
	}
}
