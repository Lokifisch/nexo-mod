package dev.nexoclient.nexomod.screen;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import dev.nexoclient.nexomod.NexoMod;
import dev.nexoclient.nexomod.paperserver.PaperServerRecord;
import dev.nexoclient.nexomod.paperserver.PaperServerRegistry;
import dev.nexoclient.nexomod.paperserver.hangar.HangarClient;

/**
 * Search form and results for {@link NexoPaperPluginBrowserScreen}. Same
 * "form rows, then result rows, in one scrolling list" shape as
 * {@code NexoChatSearchOptionList}.
 *
 * <p>Installing is two clicks, not one: the first fetches and shows the
 * latest version's author and review state (Hangar only reports those per
 * version, not per project, so they aren't known until this point); the
 * second is the actual install. Neither click is skippable — there is no
 * "always install the newest without looking" shortcut, on purpose.
 */
public class NexoPaperPluginOptionList extends NexoOptionList {
	private enum Status { IDLE, SEARCHING, DONE, FAILED }

	private final PaperServerRecord record;
	private final HangarClient hangar = new HangarClient();

	private String query = "";
	private Status status = Status.IDLE;
	private List<HangarClient.Project> results = List.of();
	private String error;

	/** The one project a version is being fetched or confirmed for, if any. */
	private Long pendingProjectId;
	private HangarClient.Version pendingVersion;
	private boolean pendingBusy;
	private String pendingError;

	private String lastInstallLine;

	public NexoPaperPluginOptionList(Minecraft mc, int width, int height, int y, int entryWidth, int entryHeight,
			int entrySpacing, PaperServerRecord record) {
		super(mc, width, height, y, entryWidth, entryHeight, entrySpacing);
		this.record = record;
	}

	@Override
	protected void addEntries() {
		addFormRow(row -> {
			int buttonWidth = 60;
			NexoTextField field = new NexoTextField(dynEntryX, 0, dynEntryWidth - buttonWidth - Entry.SPACE_SMALL, entryHeight);
			field.setMaxLength(128);
			field.setValue(query);
			field.setHint(Component.translatable("nexomod.paperServer.plugins.queryHint"));
			field.setResponder(value -> query = value);
			row.add(field);
			row.add(Button.builder(Component.translatable("nexomod.paperServer.plugins.go"), b -> submitSearch())
					.pos(dynEntryX + dynEntryWidth - buttonWidth, 0).size(buttonWidth, entryHeight).build());
		});

		Component statusLine = switch (status) {
			case IDLE -> Component.translatable("nexomod.paperServer.plugins.idle").withStyle(ChatFormatting.GRAY);
			case SEARCHING -> Component.translatable("nexomod.paperServer.plugins.searching").withStyle(ChatFormatting.YELLOW);
			case DONE -> Component.translatable("nexomod.paperServer.plugins.results", results.size()).withStyle(ChatFormatting.GRAY);
			case FAILED -> Component.translatable("nexomod.paperServer.plugins.failed", error).withStyle(ChatFormatting.RED);
		};
		addEntry(new NexoOptionList.Entry.Text(dynEntryX, dynEntryWidth, entryHeight, statusLine, null, -1));

		if (lastInstallLine != null) {
			addEntry(new NexoOptionList.Entry.Text(dynEntryX, dynEntryWidth, entryHeight,
					Component.literal(lastInstallLine).withStyle(ChatFormatting.AQUA), null, -1));
		}

		for (HangarClient.Project project : results) {
			addProjectRows(project);
		}
	}

	private void addProjectRows(HangarClient.Project project) {
		addEntry(new NexoOptionList.Entry.Text(dynEntryX, dynEntryWidth, entryHeight,
				Component.literal(project.name() + " ").withStyle(ChatFormatting.WHITE)
						.append(Component.literal("by " + project.namespace().owner() + " · " + project.category())
								.withStyle(ChatFormatting.GRAY)),
				null, -1));

		boolean pendingThisProject = pendingProjectId != null && pendingProjectId == project.id();
		if (!pendingThisProject) {
			addFormRow(row -> row.add(Button.builder(Component.translatable("nexomod.paperServer.plugins.install"),
							b -> beginInstall(project))
					.pos(dynEntryX, 0).size(dynEntryWidth, entryHeight).build()));
			return;
		}

		if (pendingBusy) {
			addEntry(new NexoOptionList.Entry.Text(dynEntryX, dynEntryWidth, entryHeight,
					Component.translatable("nexomod.paperServer.plugins.checking").withStyle(ChatFormatting.YELLOW), null, -1));
		} else if (pendingError != null) {
			addEntry(new NexoOptionList.Entry.Text(dynEntryX, dynEntryWidth, entryHeight,
					Component.literal(pendingError).withStyle(ChatFormatting.RED), null, -1));
			addFormRow(row -> row.add(Button.builder(CommonComponents.GUI_CANCEL, b -> cancelInstall())
					.pos(dynEntryX, 0).size(dynEntryWidth, entryHeight).build()));
		} else if (pendingVersion != null) {
			HangarClient.Version version = pendingVersion;
			addEntry(new NexoOptionList.Entry.Text(dynEntryX, dynEntryWidth, entryHeight,
					Component.translatable("nexomod.paperServer.plugins.confirm",
							version.name(), version.author(), version.reviewState()).withStyle(ChatFormatting.YELLOW),
					null, -1));
			addFormRow(row -> {
				int half = (dynEntryWidth - Entry.SPACE_SMALL) / 2;
				row.add(Button.builder(Component.translatable("nexomod.paperServer.plugins.confirmInstall"),
								b -> confirmInstall(project, version))
						.pos(dynEntryX, 0).size(half, entryHeight).build());
				row.add(Button.builder(CommonComponents.GUI_CANCEL, b -> cancelInstall())
						.pos(dynEntryX + half + Entry.SPACE_SMALL, 0).size(dynEntryWidth - half - Entry.SPACE_SMALL, entryHeight).build());
			});
		}
	}

	private void submitSearch() {
		status = Status.SEARCHING;
		init();
		String submittedQuery = query;
		CompletableFuture.supplyAsync(() -> {
			try {
				return hangar.search(submittedQuery, 20);
			} catch (IOException | InterruptedException e) {
				if (e instanceof InterruptedException) {
					Thread.currentThread().interrupt();
				}
				throw new java.util.concurrent.CompletionException(e);
			}
		}).whenComplete((found, err) -> Minecraft.getInstance().execute(() -> {
			if (err != null) {
				status = Status.FAILED;
				error = err.getCause() != null ? err.getCause().getMessage() : err.getMessage();
			} else {
				status = Status.DONE;
				results = found;
			}
			init();
		}));
	}

	private void beginInstall(HangarClient.Project project) {
		pendingProjectId = project.id();
		pendingVersion = null;
		pendingBusy = true;
		pendingError = null;
		init();

		CompletableFuture.supplyAsync(() -> {
			try {
				List<HangarClient.Version> versions = hangar.versions(project.namespace().owner(), project.namespace().slug(), 10);
				return versions.stream().filter(v -> v.downloads() != null && v.downloads().containsKey("PAPER")).findFirst();
			} catch (IOException | InterruptedException e) {
				if (e instanceof InterruptedException) {
					Thread.currentThread().interrupt();
				}
				throw new java.util.concurrent.CompletionException(e);
			}
		}).whenComplete((found, err) -> Minecraft.getInstance().execute(() -> {
			pendingBusy = false;
			if (err != null) {
				pendingError = err.getCause() != null ? err.getCause().getMessage() : err.getMessage();
			} else if (found.isEmpty()) {
				pendingError = "No build of this plugin supports Paper";
			} else {
				pendingVersion = found.get();
			}
			init();
		}));
	}

	private void confirmInstall(HangarClient.Project project, HangarClient.Version version) {
		pendingBusy = true;
		init();

		Path pluginsDir = PaperServerRegistry.serverDir(record.id()).resolve("plugins");
		CompletableFuture.runAsync(() -> {
			try {
				Path dest = pluginsDir.resolve(version.paperDownload().fileInfo().name());
				hangar.downloadPlugin(version, dest);
			} catch (IOException | InterruptedException e) {
				if (e instanceof InterruptedException) {
					Thread.currentThread().interrupt();
				}
				throw new java.util.concurrent.CompletionException(e);
			}
		}).whenComplete((ignored, err) -> Minecraft.getInstance().execute(() -> {
			pendingProjectId = null;
			pendingVersion = null;
			pendingBusy = false;
			pendingError = null;
			if (err != null) {
				Throwable cause = err.getCause() != null ? err.getCause() : err;
				NexoMod.LOGGER.error("[nexomod] Plugin install failed", cause);
				lastInstallLine = "Failed: " + cause.getMessage();
			} else {
				lastInstallLine = "Installed " + project.name() + " " + version.name();
			}
			init();
		}));
	}

	private void cancelInstall() {
		pendingProjectId = null;
		pendingVersion = null;
		pendingBusy = false;
		pendingError = null;
		init();
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
