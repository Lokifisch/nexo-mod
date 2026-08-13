package dev.nexoclient.nexomod.screen;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

import dev.nexoclient.nexomod.chat.NexoChatHistory;
import dev.nexoclient.nexomod.chat.NexoChatMessage;
import dev.nexoclient.nexomod.chat.NexoChatSearch;

/**
 * The search form and its results, in one scrolling list.
 *
 * <p>Query goes to the native side; server, sender and time range are applied
 * to what comes back ({@link NexoChatSearch.Filter}), because the FFI's
 * {@code chatDbSearchAsync} takes a query and a limit and nothing else. That is
 * visible to the user in exactly one way — a narrow filter over a huge history
 * can return fewer rows than the limit — and is called out in the row-count
 * line rather than hidden.
 */
public class NexoChatSearchOptionList extends NexoOptionList {
	/**
	 * Time windows offered instead of a pair of date fields. A date field means
	 * parsing, a format the player has to guess and an error state for every
	 * keystroke that isn't a date yet; "the last day" is what someone looking
	 * for a message they just saw actually wants.
	 */
	public enum Range {
		ANY(0L),
		HOUR(3_600_000L),
		DAY(86_400_000L),
		WEEK(7L * 86_400_000L),
		MONTH(30L * 86_400_000L);

		/** How far back from now, in milliseconds; 0 means no lower bound. */
		public final long millis;

		Range(long millis) {
			this.millis = millis;
		}
	}

	private static final DateTimeFormatter TIMESTAMP =
			DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

	private String query = "";
	private String serverFilter = "";
	private String senderFilter = "";
	private Range range = Range.ANY;

	public NexoChatSearchOptionList(Minecraft mc, int width, int height, int y, int entryWidth, int entryHeight, int entrySpacing) {
		super(mc, width, height, y, entryWidth, entryHeight, entrySpacing);
	}

	/** Called when a search settles; keeps the form values and redraws the rows. */
	public void refreshResults() {
		init();
	}

	@Override
	protected void addEntries() {
		if (!NexoChatHistory.isAvailable()) {
			// Not an error dialog: the native library is genuinely absent on
			// most platforms today, and this screen is reachable from a hub
			// button that has no way to know that ahead of time.
			addEntry(new NexoOptionList.Entry.Text(dynEntryX, dynEntryWidth, entryHeight,
					Component.translatable("nexomod.chat.search.unavailable").withStyle(ChatFormatting.GRAY), null, -1));
			return;
		}

		addQueryRow();
		addFilterRow();
		addRangeRow();
		addStatusRow();
		addResultRows();
	}

	private void addQueryRow() {
		addEntry(new FormRow(row -> {
			int buttonWidth = 60;
			NexoTextField field = new NexoTextField(dynEntryX, 0, dynEntryWidth - buttonWidth - Entry.SPACE_SMALL, entryHeight);
			field.setMaxLength(256);
			field.setValue(query);
			field.setHint(Component.translatable("nexomod.chat.search.queryHint"));
			field.setResponder(value -> query = value);
			row.add(field);
			row.add(Button.builder(Component.translatable("nexomod.chat.search.go"), button -> submit())
					.pos(dynEntryX + dynEntryWidth - buttonWidth, 0).size(buttonWidth, entryHeight).build());
		}));
	}

	private void addFilterRow() {
		addEntry(new FormRow(row -> {
			int half = (dynEntryWidth - Entry.SPACE_SMALL) / 2;
			NexoTextField server = new NexoTextField(dynEntryX, 0, half, entryHeight);
			server.setMaxLength(128);
			server.setValue(serverFilter);
			server.setHint(Component.translatable("nexomod.chat.search.serverHint"));
			server.setResponder(value -> serverFilter = value);
			row.add(server);

			NexoTextField sender = new NexoTextField(dynEntryX + half + Entry.SPACE_SMALL, 0,
					dynEntryWidth - half - Entry.SPACE_SMALL, entryHeight);
			sender.setMaxLength(64);
			sender.setValue(senderFilter);
			sender.setHint(Component.translatable("nexomod.chat.search.senderHint"));
			sender.setResponder(value -> senderFilter = value);
			row.add(sender);
		}));
	}

	private void addRangeRow() {
		CycleButton<Range> button = CycleButton.<Range>builder(NexoChatSearchOptionList::rangeLabel, range)
				.withValues(Range.values())
				.withTooltip(value -> Tooltip.create(Component.translatable("nexomod.chat.search.range.tooltip")))
				.create(dynEntryX, 0, dynEntryWidth, entryHeight,
						Component.translatable("nexomod.chat.search.range"), (b, value) -> range = value);
		addEntry(new FormRow(row -> row.add(button)));
	}

	private void addStatusRow() {
		Component status = switch (NexoChatSearch.status()) {
			case IDLE -> Component.translatable("nexomod.chat.search.idle").withStyle(ChatFormatting.GRAY);
			case SEARCHING -> Component.translatable("nexomod.chat.search.running").withStyle(ChatFormatting.YELLOW);
			case DONE -> Component.translatable("nexomod.chat.search.results",
					NexoChatSearch.results().size()).withStyle(ChatFormatting.GRAY);
			case FAILED -> Component.translatable("nexomod.chat.search.failed",
					NexoChatSearch.error()).withStyle(ChatFormatting.RED);
		};
		addEntry(new NexoOptionList.Entry.Text(dynEntryX, dynEntryWidth, entryHeight, status, null, -1));
	}

	private void addResultRows() {
		List<NexoChatMessage> results = NexoChatSearch.results();
		for (NexoChatMessage message : results) {
			addEntry(new NexoOptionList.Entry.Text(dynEntryX, dynEntryWidth, entryHeight, resultLine(message),
					Tooltip.create(resultTooltip(message)), 0));
		}
	}

	private void submit() {
		long since = range.millis == 0L ? 0L : System.currentTimeMillis() - range.millis;
		NexoChatSearch.submit(query, new NexoChatSearch.Filter(serverFilter, senderFilter, since, 0L),
				NexoChatSearch.MAX_RESULTS);
		init();
	}

	private static Component resultLine(NexoChatMessage message) {
		return Component.literal("<" + message.sender() + "> ").withStyle(ChatFormatting.AQUA)
				.append(Component.literal(message.message()).withStyle(ChatFormatting.WHITE));
	}

	/** The metadata that doesn't fit on the row; a result is useless without knowing when and where. */
	private static Component resultTooltip(NexoChatMessage message) {
		return Component.translatable("nexomod.chat.search.resultTooltip",
				TIMESTAMP.format(Instant.ofEpochMilli(message.timestamp())), message.server());
	}

	private static Component rangeLabel(Range range) {
		return Component.translatable("nexomod.chat.search.range." + range.name().toLowerCase(java.util.Locale.ROOT));
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

	/** A row whose widgets are supplied by a lambda, so each form line stays a few lines of code. */
	private static class FormRow extends NexoOptionList.Entry {
		FormRow(java.util.function.Consumer<java.util.List<net.minecraft.client.gui.components.AbstractWidget>> builder) {
			builder.accept(elements);
		}
	}
}
