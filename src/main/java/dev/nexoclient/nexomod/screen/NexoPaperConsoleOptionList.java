package dev.nexoclient.nexomod.screen;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import dev.nexoclient.nexomod.NexoMod;
import dev.nexoclient.nexomod.paperserver.PaperServerRecord;
import dev.nexoclient.nexomod.paperserver.PaperServerRegistry;
import dev.nexoclient.nexomod.paperserver.rcon.RconClient;

/**
 * Command box plus a tail of {@code logs/latest.log}, newest line first so
 * it's visible without scrolling. See {@link NexoPaperConsoleScreen} for why
 * this reads the log file rather than trying to push-stream it.
 */
public class NexoPaperConsoleOptionList extends NexoOptionList {
	/** Keeps the row count (and the render cost of a long session's log) bounded. */
	private static final int MAX_LINES = 300;
	private static final Duration RCON_TIMEOUT = Duration.ofSeconds(5);

	private final PaperServerRecord record;

	private String commandInput = "";
	private List<String> logLines = List.of();
	private boolean sending;

	public NexoPaperConsoleOptionList(Minecraft mc, int width, int height, int y, int entryWidth, int entryHeight,
			int entrySpacing, PaperServerRecord record) {
		super(mc, width, height, y, entryWidth, entryHeight, entrySpacing);
		this.record = record;
		loadLog();
	}

	/** Re-reads the log and, if it changed, redraws. Called on a timer by {@link NexoPaperConsoleScreen}. */
	void refreshLog() {
		List<String> before = logLines;
		loadLog();
		if (!logLines.equals(before)) {
			init();
		}
	}

	private void loadLog() {
		Path logFile = PaperServerRegistry.serverDir(record.id()).resolve("logs").resolve("latest.log");
		try {
			List<String> all = Files.readAllLines(logFile, StandardCharsets.UTF_8);
			int from = Math.max(0, all.size() - MAX_LINES);
			List<String> tail = new ArrayList<>(all.subList(from, all.size()));
			Collections.reverse(tail);
			logLines = tail;
		} catch (IOException e) {
			logLines = List.of("(no log yet)");
		}
	}

	@Override
	protected void addEntries() {
		addFormRow(row -> {
			int buttonWidth = 80;
			NexoTextField field = new NexoTextField(dynEntryX, 0, dynEntryWidth - buttonWidth - Entry.SPACE_SMALL, entryHeight);
			field.setMaxLength(256);
			field.setValue(commandInput);
			field.setHint(Component.translatable("nexomod.paperServer.console.commandHint"));
			field.setResponder(value -> commandInput = value);
			row.add(field);
			row.add(Button.builder(Component.translatable(sending
									? "nexomod.paperServer.console.sending"
									: "nexomod.paperServer.console.send"), b -> sendCommand())
					.pos(dynEntryX + dynEntryWidth - buttonWidth, 0).size(buttonWidth, entryHeight).build());
		});

		for (String line : logLines) {
			addEntry(new NexoOptionList.Entry.Text(dynEntryX, dynEntryWidth, entryHeight,
					Component.literal(line).withStyle(ChatFormatting.GRAY), null, -1));
		}
	}

	private void sendCommand() {
		if (sending || commandInput.isBlank()) {
			return;
		}
		String command = commandInput.trim();
		commandInput = "";
		sending = true;
		init();

		CompletableFuture.supplyAsync(() -> {
			try (RconClient rcon = RconClient.connect("127.0.0.1", record.rcon().port(), record.rcon().password(), RCON_TIMEOUT)) {
				return rcon.command(command);
			} catch (IOException e) {
				NexoMod.LOGGER.warn("[nexomod] Console command \"{}\" failed", command, e);
				return "(command failed: " + e.getMessage() + ")";
			}
		}).whenComplete((response, error) -> Minecraft.getInstance().execute(() -> {
			sending = false;
			// The response is also what the server just logged, so a
			// refresh is enough — no need to inject it into logLines by hand.
			refreshLog();
		}));
	}

	private void addFormRow(Consumer<List<AbstractWidget>> builder) {
		addEntry(new FormRow(builder));
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

	private static class FormRow extends NexoOptionList.Entry {
		FormRow(Consumer<List<AbstractWidget>> builder) {
			builder.accept(elements);
		}
	}
}
