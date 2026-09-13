package dev.nexoclient.nexomod.paperserver;

import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.brigadier.CommandDispatcher;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Milestone 1 of Phase 7 (see {@code Mod/ROADMAP.md}): a thin command-line
 * front end for {@link PaperServerOrchestrator}, kept around for manual
 * testing now that the real UI ({@code NexoPaperServerScreen} and friends)
 * exists too. All the actual convert/host/stop/revert logic lives in the
 * orchestrator, not here — this class only reads the current world and
 * prints progress to the log.
 */
public final class PaperServerDebug {
	private static final Logger LOGGER = LoggerFactory.getLogger("nexomod/paperserver");

	/**
	 * The most recently converted world, so {@code revert} has an id and a
	 * save path to work with. The real UI doesn't need this — it derives both
	 * from whatever world the player is standing in when they click "convert
	 * back" — this is purely so the command-line path stays usable on its own.
	 */
	private static volatile PendingRevert LAST_CONVERTED;

	private PaperServerDebug() {}

	public static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("nexopaperdebug")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.literal("convert")
						.executes(ctx -> convert(ctx, false))
						.then(Commands.literal("acceptEula").executes(ctx -> convert(ctx, true))))
				.then(Commands.literal("revert").executes(PaperServerDebug::revert)));
	}

	private static int convert(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx, boolean acceptEula) {
		Minecraft client = Minecraft.getInstance();
		IntegratedServer server = client.getSingleplayerServer();
		if (server == null) {
			ctx.getSource().sendFailure(Component.literal("[nexo] Not in a singleplayer world."));
			return 0;
		}
		if (!acceptEula) {
			ctx.getSource().sendFailure(Component.literal(
					"[nexo] Read https://aka.ms/MinecraftEULA, then run /nexopaperdebug convert acceptEula to agree to it."));
			return 0;
		}

		Path save = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
		String worldName = save.getFileName().toString();
		String minecraftVersion = FabricLoader.getInstance().getModContainer("minecraft")
				.map(mod -> mod.getMetadata().getVersion().getFriendlyString())
				.orElseThrow(() -> new IllegalStateException("Could not resolve the running Minecraft version"));

		ctx.getSource().sendSuccess(() -> Component.literal("[nexo] Leaving \"" + worldName + "\" and converting it to a Paper server..."), false);

		// disconnectFromWorld must run on the client thread; the orchestrator's work is blocking I/O and must not.
		String playerName = client.getUser().getName();
		client.execute(() -> {
			client.disconnectFromWorld(ClientLevel.DEFAULT_QUIT_MESSAGE);
			PaperServerOrchestrator.convertAndHost(save, worldName, minecraftVersion, "mod",
							PaperServerOrchestrator.HostOptions.defaults(playerName))
					.thenAccept(record -> {
						LAST_CONVERTED = new PendingRevert(record.id());
						LOGGER.info("[nexomod] Paper server \"{}\" is up on localhost:{} (rcon {}). "
										+ "Direct Connect to it manually, or run /nexopaperdebug revert once you're done.",
								worldName, record.port(), record.rcon().port());
					})
					.exceptionally(e -> {
						LOGGER.error("[nexomod] Paper server convert failed", e);
						return null;
					});
		});
		return 1;
	}

	private static int revert(com.mojang.brigadier.context.CommandContext<CommandSourceStack> ctx) {
		PendingRevert pending = LAST_CONVERTED;
		if (pending == null) {
			ctx.getSource().sendFailure(Component.literal("[nexo] No world converted by this command in this session — nothing to revert."));
			return 0;
		}
		ctx.getSource().sendSuccess(() -> Component.literal("[nexo] Stopping the Paper server and converting \"" + pending.id + "\" back..."), false);

		LAST_CONVERTED = null;
		PaperServerOrchestrator.stopAndRevert(pending.id)
				.thenAccept(backup -> LOGGER.info("[nexomod] Reverted. Backup at {}", backup))
				.exceptionally(e -> {
					LOGGER.error("[nexomod] Paper server revert failed", e);
					return null;
				});
		return 1;
	}

	private record PendingRevert(String id) {}
}
