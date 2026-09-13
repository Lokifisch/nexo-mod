package dev.nexoclient.nexomod.lantunnel;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.brigadier.CommandDispatcher;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * Entry point for the LAN-over-internet tunnel feature: "Open to LAN" ->
 * anyone can join, anywhere, no port forwarding. Adapted from e4mc's
 * {@code E4mcClient} (https://github.com/vgskye/e4mc-minecraft-architectury,
 * MIT) — see Mod/THIRD-PARTY-NOTICES.md. The actual host-side hook lives in
 * {@link dev.nexoclient.nexomod.mixin.LanTunnelHostMixin}; this class just
 * holds the active session and exposes {@code /nexolan stop}.
 *
 * <p>Scope note: this is the relay-only path (see {@link LanTunnelSession}
 * doc). e4mc's optional direct peer-to-peer path ("Dialtone") and its
 * LAN-world ban/whitelist-restoration commands aren't ported (yet) —
 * neither is required for "anyone can join my LAN world."
 */
public final class LanTunnel {
	public static final Logger LOGGER = LoggerFactory.getLogger("nexomod/lantunnel");

	public static volatile LanTunnelSession session;

	/**
	 * Friend-hosting tunnels for Paper servers (see {@code Mod/ROADMAP.md}
	 * Phase 7), keyed by server id — separate from {@link #session} above,
	 * which is the singleplayer "Open to LAN" tunnel and unrelated: a player
	 * could have a LAN world open and be friend-hosting a Paper server at
	 * the same time, so the two must not share one slot.
	 */
	private static final Map<String, LanTunnelSession> paperTunnels = new ConcurrentHashMap<>();

	private LanTunnel() {}

	/** Tunnels {@code localPort} (the Paper server's game port, never its RCON port) to the relay. */
	public static LanTunnelSession startForPaperServer(String serverId, int localPort) {
		stopForPaperServer(serverId);
		EventLoopGroup group = new NioEventLoopGroup(1);
		LanTunnelSession newSession = new LanTunnelSession(LocalPortForward.forwardingHandler(group, localPort), group);
		paperTunnels.put(serverId, newSession);
		newSession.startAsync();
		return newSession;
	}

	public static void stopForPaperServer(String serverId) {
		LanTunnelSession existing = paperTunnels.remove(serverId);
		if (existing != null && existing.state != LanTunnelSession.State.STOPPED) {
			existing.stop();
		}
	}

	public static LanTunnelSession paperTunnel(String serverId) {
		return paperTunnels.get(serverId);
	}

	public static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("nexolan")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.literal("stop").executes(ctx -> {
					LanTunnelSession activeSession = session;
					if (activeSession != null && activeSession.state != LanTunnelSession.State.STOPPED) {
						activeSession.stop();
						ctx.getSource().sendSuccess(() -> Component.translatable("text.nexomod.lantunnel.closeServer"), true);
					} else {
						ctx.getSource().sendFailure(Component.translatable("text.nexomod.lantunnel.serverAlreadyClosed"));
					}
					return 1;
				})));
	}
}
