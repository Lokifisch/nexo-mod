package dev.nexoclient.nexomod.tactical.locate;

import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.permissions.Permissions;

import dev.nexoclient.nexomod.coords.CoordObfuscator;

/**
 * {@code /locate structure|biome}, usable without op once a seed is saved.
 *
 * <p>A client command shadows the server's command of the same name, so this
 * <b>forwards</b> to the real {@code /locate} whenever the player is allowed to
 * run it: an operator gets the server's answer (which knows the server's actual
 * datapacks), and everyone else gets the answer worked out from the seed saved
 * with {@code /nexoseed set}. {@code /locate poi} and anything else this does not
 * define fails to parse here and falls through to the server untouched.
 *
 * <p>Coordinates go through {@link CoordObfuscator} like every other place the
 * mod prints a position, so a screenshot of the result does not undo Position
 * Obscuring. The click-to-teleport suggestion is dropped in that case, since
 * the fake position would be wrong to teleport to.
 */
public final class NexoLocateCommand {
	private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "nexo-locate");
		thread.setDaemon(true);
		return thread;
	});

	private NexoLocateCommand() {
	}

	public static void register() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> {
			dispatcher.register(ClientCommands.literal("locate")
					.then(ClientCommands.literal("structure")
							.then(ClientCommands.argument("id", IdentifierArgument.id())
									.suggests((ctx, builder) -> SharedSuggestionProvider.suggestResource(SeedLocator.structureIds(), builder))
									.executes(ctx -> locate(ctx, "structure"))))
					.then(ClientCommands.literal("biome")
							.then(ClientCommands.argument("id", IdentifierArgument.id())
									.suggests((ctx, builder) -> SharedSuggestionProvider.suggestResource(SeedLocator.biomeIds(), builder))
									.executes(ctx -> locate(ctx, "biome")))));

			dispatcher.register(ClientCommands.literal("nexoseed")
					.then(ClientCommands.literal("set")
							.then(ClientCommands.argument("seed", StringArgumentType.greedyString())
									.executes(NexoLocateCommand::setSeed)))
					.then(ClientCommands.literal("version")
							.then(ClientCommands.argument("version", StringArgumentType.word())
									.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
											java.util.Arrays.stream(WorldgenVersion.values()).map(WorldgenVersion::label), builder))
									.executes(NexoLocateCommand::setVersion)))
					.then(ClientCommands.literal("get").executes(NexoLocateCommand::getSeed))
					.then(ClientCommands.literal("forget").executes(NexoLocateCommand::forgetSeed))
					.then(ClientCommands.literal("index").executes(ctx -> {
						Minecraft client = ctx.getSource().getClient();
						// Deferred: the chat screen closes itself after running a command,
						// which would take a screen opened right now down with it.
						client.schedule(() -> client.setScreen(new NexoSeedIndexScreen(null)));
						return 1;
					})));
		});

		// A singleplayer world's seed is known, so it goes in the index without being asked —
		// and /locate then works there even with cheats off. Also warms the worldgen data
		// load (a second or two) while the player is still loading in, not at the first /locate.
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			if (client.getSingleplayerServer() != null) {
				SeedIndex.currentPlace().ifPresent(place -> SeedIndex.get().put(place.key(), place.label(), "", true,
						client.getSingleplayerServer().overworld().getSeed(), SeedIndex.Source.SINGLEPLAYER, null));
			}
			if (client.getSingleplayerServer() == null) {
				CommunitySeedDatabase.onJoin(client, handler.getConnection().getRemoteAddress(), WORKER);
			}
			WORKER.execute(() -> {
				try {
					SeedWorldgen.data();
				} catch (Exception e) {
					// Retried, with the error shown to the player, by the first real /locate.
				}
			});
		});
	}

	private static int locate(CommandContext<FabricClientCommandSource> ctx, String kind) {
		FabricClientCommandSource source = ctx.getSource();
		Identifier id = ctx.getArgument("id", Identifier.class);

		if (source.getPlayer().permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)) {
			source.getClient().getConnection().sendCommand("locate " + kind + " " + id);
			return 1;
		}
		Optional<SeedIndex.Place> place = SeedIndex.currentPlace();
		Optional<SeedIndex.Entry> saved = place.flatMap(p -> SeedIndex.get().find(p.key()));
		if (saved.isEmpty()) {
			source.sendError(Component.translatable("nexomod.locate.noSeed"));
			return 0;
		}
		WorldgenVersion version = WorldgenVersion.of(saved.get().version);
		if (!version.supported()) {
			source.sendError(Component.translatable("nexomod.locate.unsupportedVersion", version.label()));
			return 0;
		}
		long seed = saved.get().seed;
		var dimension = source.getLevel().dimension();
		BlockPos origin = source.getPlayer().blockPosition();
		source.sendFeedback(Component.translatable("nexomod.locate.working", id.toString()));

		Minecraft client = source.getClient();
		WORKER.execute(() -> {
			Supplier<Component> reply;
			try {
				Optional<BlockPos> hit = kind.equals("structure")
						? SeedLocator.structure(seed, dimension, id, origin)
						: SeedLocator.biome(seed, dimension, id, origin);
				reply = () -> hit.map(pos -> found(id, pos, origin, kind.equals("biome")))
						.orElseGet(() -> Component.translatable("nexomod.locate.notFound", id.toString())
								.withStyle(ChatFormatting.RED));
			} catch (Exception e) {
				reply = () -> Component.translatable("nexomod.locate.failed", String.valueOf(e.getMessage()))
						.withStyle(ChatFormatting.RED);
			}
			Supplier<Component> message = reply;
			client.execute(() -> source.sendFeedback(message.get()));
		});
		return 1;
	}

	private static Component found(Identifier id, BlockPos pos, BlockPos origin, boolean withY) {
		boolean fake = CoordObfuscator.active();
		BlockPos shown = fake ? CoordObfuscator.obscure(pos) : pos;
		long distance = Math.round(Math.sqrt(pos.distToCenterSqr(origin.getX() + 0.5, pos.getY(), origin.getZ() + 0.5)));

		MutableComponent coords = Component.literal("[" + shown.getX() + ", ~, " + shown.getZ() + "]")
				.withStyle(ChatFormatting.GREEN);
		if (!fake) {
			coords.withStyle(style -> style
					.withClickEvent(new ClickEvent.SuggestCommand("/tp @s " + shown.getX() + " ~ " + shown.getZ()))
					.withHoverEvent(new HoverEvent.ShowText(Component.translatable("chat.coordinates.tooltip"))));
		}
		// Plain coordinates, not the tp command: for pasting into a waypoint or a message.
		String plain = withY ? shown.getX() + " " + shown.getY() + " " + shown.getZ() : shown.getX() + " ~ " + shown.getZ();
		MutableComponent copy = Component.literal("\u2398").withStyle(style -> style
				.withColor(ChatFormatting.AQUA)
				.withClickEvent(new ClickEvent.CopyToClipboard(plain))
				.withHoverEvent(new HoverEvent.ShowText(Component.translatable("nexomod.locate.copy", plain))));
		return Component.translatable("nexomod.locate.found", id.toString(), coords.append(" ").append(copy), distance);
	}

	private static int setSeed(CommandContext<FabricClientCommandSource> ctx) {
		FabricClientCommandSource source = ctx.getSource();
		Optional<SeedIndex.Place> place = SeedIndex.currentPlace();
		OptionalLong seed = SeedIndex.tryParseSeed(StringArgumentType.getString(ctx, "seed"));
		if (place.isEmpty() || seed.isEmpty()) {
			source.sendError(Component.translatable("nexomod.locate.notConnected"));
			return 0;
		}
		SeedIndex.Place p = place.get();
		SeedIndex.get().put(p.key(), p.label(), p.address(), p.world(), seed.getAsLong(), SeedIndex.Source.MANUAL, null);
		source.sendFeedback(Component.translatable("nexomod.locate.saved", p.label(), seed.getAsLong()));
		return 1;
	}

	private static int setVersion(CommandContext<FabricClientCommandSource> ctx) {
		String label = StringArgumentType.getString(ctx, "version");
		Optional<WorldgenVersion> version = WorldgenVersion.find(label);
		Optional<SeedIndex.Entry> saved = SeedIndex.currentPlace().flatMap(p -> SeedIndex.get().find(p.key()));
		if (version.isEmpty()) {
			ctx.getSource().sendError(Component.translatable("nexomod.locate.unknownVersion", label));
			return 0;
		}
		if (saved.isEmpty()) {
			ctx.getSource().sendError(Component.translatable("nexomod.locate.noSeed"));
			return 0;
		}
		SeedIndex.get().setVersion(saved.get(), version.get());
		ctx.getSource().sendFeedback(Component.translatable("nexomod.locate.versionSaved", saved.get().label, version.get().label()));
		return 1;
	}

	private static int getSeed(CommandContext<FabricClientCommandSource> ctx) {
		Optional<SeedIndex.Entry> saved = SeedIndex.currentPlace().flatMap(p -> SeedIndex.get().find(p.key()));
		if (saved.isEmpty()) {
			ctx.getSource().sendError(Component.translatable("nexomod.locate.noSeed"));
			return 0;
		}
		ctx.getSource().sendFeedback(Component.translatable("nexomod.locate.current", saved.get().label, saved.get().seed, WorldgenVersion.of(saved.get().version).label()));
		return 1;
	}

	private static int forgetSeed(CommandContext<FabricClientCommandSource> ctx) {
		Optional<SeedIndex.Entry> saved = SeedIndex.currentPlace().flatMap(p -> SeedIndex.get().find(p.key()));
		if (saved.isEmpty()) {
			ctx.getSource().sendError(Component.translatable("nexomod.locate.noSeed"));
			return 0;
		}
		SeedIndex.get().remove(saved.get());
		ctx.getSource().sendFeedback(Component.translatable("nexomod.locate.forgotten", saved.get().label));
		return 1;
	}
}
