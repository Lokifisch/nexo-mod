package dev.nexoclient.nexomod.screen;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelResource;

import dev.nexoclient.nexomod.NexoMod;
import dev.nexoclient.nexomod.paperserver.PaperServerOrchestrator;
import dev.nexoclient.nexomod.paperserver.PaperServerRecord;
import dev.nexoclient.nexomod.paperserver.PaperServerRegistry;
import dev.nexoclient.nexomod.servers.NexoQuickConnect;
import dev.nexoclient.nexomod.servers.NexoServerEntry;

/**
 * Row-per-known-server list for {@link NexoPaperServerScreen}, plus (when
 * standing in a singleplayer world without a running server for it) a
 * "convert this world" row. See {@code Mod/ROADMAP.md} Phase 7.
 */
public class NexoPaperServerOptionList extends NexoOptionList {
	public NexoPaperServerOptionList(Minecraft mc, int width, int height, int y, int entryWidth, int entryHeight, int entrySpacing) {
		super(mc, width, height, y, entryWidth, entryHeight, entrySpacing);
	}

	@Override
	protected void addEntries() {
		Minecraft client = Minecraft.getInstance();
		IntegratedServer singleplayer = client.getSingleplayerServer();
		if (singleplayer != null) {
			addConvertRowIfApplicable(singleplayer);
		} else {
			addEntry(new NexoOptionList.Entry.Text(dynEntryX, dynEntryWidth, entryHeight,
					Component.translatable("nexomod.paperServer.notSingleplayer"), null, -1));
		}

		List<PaperServerRecord> servers = PaperServerRegistry.listAll();
		if (servers.isEmpty()) {
			addEntry(new NexoOptionList.Entry.Text(dynEntryX, dynEntryWidth, entryHeight,
					Component.translatable("nexomod.paperServer.none"), null, -1));
		}
		for (PaperServerRecord record : servers) {
			addEntry(new ServerRow(dynEntryX, dynEntryWidth, entryHeight, record));
			if (PaperServerRecord.Status.RUNNING.equals(record.status())) {
				addEntry(new FriendHostingRow(dynEntryX, dynEntryWidth, entryHeight, record));
			}
		}
	}

	private void toggleFriendHosting(PaperServerRecord record, boolean currentlyActive) {
		if (currentlyActive) {
			PaperServerOrchestrator.stopFriendHosting(record.id());
			init();
			return;
		}
		PaperServerOrchestrator.startFriendHosting(record.id())
				.whenComplete((updated, error) -> Minecraft.getInstance().execute(() -> {
					if (error != null) {
						NexoMod.LOGGER.error("[nexomod] Could not start friend-hosting for \"{}\"", record.id(), error);
					}
					init();
				}));
	}

	private void addConvertRowIfApplicable(IntegratedServer singleplayer) {
		Path save = singleplayer.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
		String worldName = save.getFileName().toString();
		String id = PaperServerOrchestrator.idFor(worldName);
		Optional<PaperServerRecord> existing = PaperServerRegistry.read(id);
		boolean convertible = existing.isEmpty()
				|| PaperServerRecord.Status.STOPPED.equals(existing.get().status())
				|| PaperServerRecord.Status.ERROR.equals(existing.get().status());
		if (!convertible) {
			return;
		}
		String playerName = Minecraft.getInstance().getUser().getName();
		addEntry(new NexoOptionList.Entry.ActionButton(dynEntryX, dynEntryWidth, entryHeight,
				Component.translatable("nexomod.paperServer.convert"), null, -1, button ->
						Minecraft.getInstance().setScreen(new NexoPaperHostSettingsScreen(screen, playerName,
								options -> beginConvert(save, worldName, options)))));
	}

	private void beginConvert(Path save, String worldName, PaperServerOrchestrator.HostOptions options) {
		Minecraft client = Minecraft.getInstance();
		String minecraftVersion = FabricLoader.getInstance().getModContainer("minecraft")
				.map(mod -> mod.getMetadata().getVersion().getFriendlyString())
				.orElse("unknown");

		// disconnectFromWorld must run on the client thread; everything after it is blocking I/O.
		client.execute(() -> {
			client.disconnectFromWorld(ClientLevel.DEFAULT_QUIT_MESSAGE);
			client.setScreen(waitingScreen(Component.translatable("nexomod.paperServer.converting")));

			PaperServerOrchestrator.convertAndHost(save, worldName, minecraftVersion, "mod", options)
					.whenComplete((record, error) -> client.execute(() -> {
						if (error != null) {
							NexoMod.LOGGER.error("[nexomod] Converting \"{}\" to a Paper server failed", worldName, error);
							client.setScreen(new TitleScreen());
							return;
						}
						NexoServerEntry entry = new NexoServerEntry();
						entry.name = record.name();
						entry.address = "localhost:" + record.port();
						NexoQuickConnect.switchTo(entry);
					}));
		});
	}

	private void beginRevert(PaperServerRecord record) {
		Minecraft client = Minecraft.getInstance();

		client.execute(() -> {
			if (client.level != null) {
				client.disconnectFromWorld(ClientLevel.DEFAULT_QUIT_MESSAGE);
			}
			client.setScreen(waitingScreen(Component.translatable("nexomod.paperServer.reverting")));

			PaperServerOrchestrator.stopAndRevert(record.id())
					.whenComplete((backup, error) -> client.execute(() -> {
						if (error != null) {
							NexoMod.LOGGER.error("[nexomod] Reverting \"{}\" to singleplayer failed", record.id(), error);
						} else {
							NexoMod.LOGGER.info("[nexomod] Reverted \"{}\" to singleplayer. Backup at {}", record.id(), backup);
						}
						client.setScreen(new TitleScreen());
					}));
		});
	}

	private SigningInScreen waitingScreen(Component status) {
		return new SigningInScreen(new TitleScreen(), new AtomicBoolean(),
				Component.translatable("nexomod.paperServer.title"), status);
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

	private class ServerRow extends NexoOptionList.Entry {
		ServerRow(int x, int width, int height, PaperServerRecord record) {
			Component label = Component.literal(record.name() + " — ").append(statusText(record));

			boolean canRevert = PaperServerRecord.Status.RUNNING.equals(record.status());
			int consoleWidth = canRevert ? 90 : 0;
			int pluginsWidth = canRevert ? 90 : 0;
			int revertWidth = canRevert ? 130 : 0;
			int buttons = canRevert ? consoleWidth + pluginsWidth + revertWidth + SPACE_SMALL * 2 : 0;
			int textWidth = canRevert ? width - buttons - SPACE_SMALL : width;

			int widgetWidth = Minecraft.getInstance().font.width(label.getString());
			elements.add(new net.minecraft.client.gui.components.StringWidget(
					x, 0, Math.min(textWidth, Math.max(widgetWidth, 40)), height, label, Minecraft.getInstance().font));

			if (canRevert) {
				int consoleX = x + textWidth + SPACE_SMALL;
				int pluginsX = consoleX + consoleWidth + SPACE_SMALL;
				int revertX = pluginsX + pluginsWidth + SPACE_SMALL;
				elements.add(NexoButton.builder(Component.translatable("nexomod.paperServer.console"),
								() -> Minecraft.getInstance().setScreen(new NexoPaperConsoleScreen(screen, record)))
						.bounds(consoleX, 0, consoleWidth, height).build());
				elements.add(NexoButton.builder(Component.translatable("nexomod.paperServer.plugins"),
								() -> Minecraft.getInstance().setScreen(new NexoPaperPluginBrowserScreen(screen, record)))
						.bounds(pluginsX, 0, pluginsWidth, height).build());
				elements.add(NexoButton.builder(Component.translatable("nexomod.paperServer.revert"), () -> beginRevert(record))
						.bounds(revertX, 0, revertWidth, height).build());
			}
		}

		private Component statusText(PaperServerRecord record) {
			ChatFormatting color = switch (record.status()) {
				case PaperServerRecord.Status.RUNNING -> ChatFormatting.GREEN;
				case PaperServerRecord.Status.ERROR -> ChatFormatting.RED;
				default -> ChatFormatting.YELLOW;
			};
			Component text = PaperServerRecord.Status.RUNNING.equals(record.status())
					? Component.translatable("nexomod.paperServer.status.running", record.port())
					: Component.translatable("nexomod.paperServer.status." + record.status());
			return text.copy().withStyle(color);
		}
	}

	/**
	 * Friend-hosting toggle for a running server — tunnels the game port
	 * through the existing LAN-tunnel relay so friends can join without
	 * port forwarding, same as vanilla's "Open to LAN". Never tunnels RCON;
	 * see {@code LocalPortForward}.
	 */
	private class FriendHostingRow extends NexoOptionList.Entry {
		FriendHostingRow(int x, int width, int height, PaperServerRecord record) {
			boolean active = record.friendHosting().tunnelActive() && record.friendHosting().domain() != null;
			int buttonWidth = 130;
			int textWidth = width - buttonWidth - SPACE_SMALL;

			Component label = (active
					? Component.translatable("nexomod.paperServer.friendHosting.active", record.friendHosting().domain())
					: Component.translatable("nexomod.paperServer.friendHosting.inactive"))
					.copy().withStyle(active ? ChatFormatting.GREEN : ChatFormatting.GRAY);
			elements.add(new net.minecraft.client.gui.components.StringWidget(x, 0, textWidth, height, label, Minecraft.getInstance().font));

			Component buttonLabel = Component.translatable(active
					? "nexomod.paperServer.friendHosting.stop"
					: "nexomod.paperServer.friendHosting.start");
			elements.add(NexoButton.builder(buttonLabel, () -> toggleFriendHosting(record, active))
					.bounds(x + textWidth + SPACE_SMALL, 0, buttonWidth, height).build());
		}
	}
}
