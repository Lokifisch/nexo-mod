package dev.nexoclient.nexomod.emotes;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import com.zigythebird.playeranimcore.animation.Animation;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Avatar;

import dev.nexoclient.nexomod.NexoMod;
import dev.nexoclient.nexomod.screen.NexoConfig;

/**
 * Shows emotes between Nexo players on a server that can't carry them: a vanilla
 * server drops custom packets, so these go through a relay instead. Off unless the
 * player turned it on, and connected only while on a multiplayer server.
 *
 * <p>Other players' messages are untrusted. A bought emote needs a valid ticket for
 * the player it claims to be from. A starter needs none, and the room key comes from
 * the server's address, so anyone guessing the address could make a tab-listed player
 * appear to wave or stop, for whoever has Sync on. Accepted: it is only ever visible
 * to Nexo players, and the worst outcome is a misleading wave.
 */
public final class NexoEmoteRelay implements NexoEmotes.Listener {
	private static final String URL = FabricLoader.getInstance().isDevelopmentEnvironment() && System.getenv("NEXOMOD_EMOTE_RELAY") != null
			? System.getenv("NEXOMOD_EMOTE_RELAY") : "wss://nexomc.dev/emotes/ws";
	private static final long REANNOUNCE_MILLIS = 5_000L;
	/** A looping emote re-announces well inside this; past it, its player is gone. */
	private static final long SILENCE_MILLIS = 12_000L;
	private static final long MIN_GAP_PER_PLAYER_MILLIS = 300L;
	private static final int MAX_FRAME = 400;
	private static final int MAX_PENDING = 64;
	private static final NexoEmoteRelay INSTANCE = new NexoEmoteRelay();

	private record Showing(String emote, long heard) {
	}

	/** Which connection attempt is current: a later one or a leave makes older ones stale. */
	private int generation;
	private final java.util.concurrent.atomic.AtomicInteger pending = new java.util.concurrent.atomic.AtomicInteger();
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
	private final Map<UUID, Showing> showing = new HashMap<>();
	private final Map<UUID, Long> lastFrom = new HashMap<>();
	private volatile WebSocket socket;
	private byte[] key;
	private String address;
	private int attempt;
	private long retryAt;
	private long lastAnnounce;
	private long playStarted;

	private NexoEmoteRelay() {
	}

	public static void register() {
		NexoEmotes.setListener(INSTANCE);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(INSTANCE::leave));
	}

	static void tick(Minecraft mc) {
		INSTANCE.onTick(mc);
	}

	private String wantedAddress(Minecraft mc) {
		if (!NexoConfig.get().emoteSyncEnabled() || mc.player == null || mc.getCurrentServer() == null || mc.isLocalServer()) {
			return null;
		}
		return mc.getCurrentServer().ip;
	}

	private void onTick(Minecraft mc) {
		String wanted = wantedAddress(mc);
		if (wanted == null || !wanted.equals(address)) {
			leave();
		}
		long now = System.currentTimeMillis();
		if (wanted != null && socket == null && address == null && now >= retryAt) {
			join(wanted);
		}
		// Keeps someone who walks up in step, with the relay holding no state.
		NexoEmotes.Emote current = NexoEmotes.playing();
		if (current != null && socket != null && now - lastAnnounce >= REANNOUNCE_MILLIS) {
			send(NexoEmoteWire.PLAY, current, (now - playStarted) / 50F);
		}
		for (Iterator<Map.Entry<UUID, Showing>> it = showing.entrySet().iterator(); it.hasNext();) {
			Map.Entry<UUID, Showing> entry = it.next();
			if (now - entry.getValue().heard() > SILENCE_MILLIS) {
				Avatar avatar = NexoEmotes.loadedPlayer(entry.getKey());
				if (avatar != null) {
					NexoEmotes.hide(avatar);
				}
				it.remove();
			}
		}
	}

	private void join(String wanted) {
		address = wanted;
		key = NexoEmoteWire.key(wanted);
		int mine = ++generation;
		byte[] room = NexoEmoteWire.room(wanted);
		http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(8)).buildAsync(URI.create(URL), new Receiver(key))
				.whenComplete((created, connectError) -> {
					// Stale means leave it alone: its socket just closes, the current state is someone else's.
					if (connectError != null) {
						Minecraft.getInstance().execute(() -> {
							if (mine == generation) {
								failed();
							}
						});
						return;
					}
					created.sendBinary(ByteBuffer.wrap(room), true).whenComplete((open, error) -> Minecraft.getInstance().execute(() -> {
						if (mine != generation) {
							created.abort();
						} else if (error != null) {
							created.abort();
							failed();
						} else {
							attempt = 0;
							socket = created;
						}
					}));
				});
	}

	private void failed() {
		socket = null;
		address = null;
		attempt++;
		// Up to five minutes between tries: a relay being down must cost nearly nothing.
		retryAt = System.currentTimeMillis() + Math.min(300_000L, 5_000L << Math.min(attempt, 6));
	}

	private void leave() {
		generation++;
		WebSocket open = socket;
		socket = null;
		address = null;
		key = null;
		if (open != null) {
			open.sendClose(WebSocket.NORMAL_CLOSURE, "").exceptionally(error -> null);
		}
		showing.keySet().forEach(player -> {
			Avatar avatar = NexoEmotes.loadedPlayer(player);
			if (avatar != null) {
				NexoEmotes.hide(avatar);
			}
		});
		showing.clear();
		lastFrom.clear();
	}

	@Override
	public void played(NexoEmotes.Emote emote) {
		playStarted = System.currentTimeMillis();
		send(NexoEmoteWire.PLAY, emote, 0F);
	}

	@Override
	public void stopped() {
		send(NexoEmoteWire.STOP, null, 0F);
	}

	private void send(int action, NexoEmotes.Emote emote, float tick) {
		WebSocket open = socket;
		Minecraft mc = Minecraft.getInstance();
		if (open == null || key == null || mc.player == null) {
			return;
		}
		lastAnnounce = System.currentTimeMillis();
		try {
			String id = emote == null ? "stop" : emote.id();
			byte[] frame = NexoEmoteWire.encode(new NexoEmoteWire.Message(action, mc.player.getUUID(), lastAnnounce, tick, id, NexoEmoteAccount.ticket(id)), key);
			open.sendBinary(ByteBuffer.wrap(frame), true).exceptionally(error -> null);
		} catch (Exception e) {
			NexoMod.LOGGER.debug("[nexomod] Emotes: could not send to the relay.");
		}
	}

	private void receive(NexoEmoteWire.Message message, byte[] frameKey) {
		// A frame from the room just left is dropped here.
		if (frameKey != key) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		long now = System.currentTimeMillis();
		if (message == null || mc.player == null || mc.getConnection() == null
				|| message.player().equals(mc.player.getUUID())
				|| Math.abs(now - message.sentMillis()) > NexoEmoteWire.MAX_AGE_MILLIS
				|| mc.getConnection().getPlayerInfo(message.player()) == null) {
			return;
		}
		Long last = lastFrom.get(message.player());
		// Never a stop: a quick cancel right after starting must still reach everyone.
		if (message.action() == NexoEmoteWire.PLAY && last != null && now - last < MIN_GAP_PER_PLAYER_MILLIS) {
			return;
		}
		if (lastFrom.size() > 2048) {
			lastFrom.clear();
		}
		lastFrom.put(message.player(), now);

		Avatar avatar = NexoEmotes.loadedPlayer(message.player());
		if (message.action() == NexoEmoteWire.STOP) {
			showing.remove(message.player());
			if (avatar != null) {
				NexoEmotes.hide(avatar);
			}
			return;
		}
		Animation animation = resolve(message, now);
		if (animation == null || avatar == null) {
			return;
		}
		Showing current = showing.get(message.player());
		showing.put(message.player(), new Showing(message.emote(), now));
		// A re-announce of what is already playing only proves they are still there.
		// Unless the player's model is new (they left range and came back): its controller is idle.
		if (current == null || !current.emote().equals(message.emote()) || !NexoEmotes.isShowing(avatar)) {
			NexoEmotes.show(avatar, animation, message.tick());
		}
	}

	private Animation resolve(NexoEmoteWire.Message message, long now) {
		if (message.emote().matches("[0-9a-f]{64}")) {
			return NexoEmoteTicket.verify(message.ticket(), message.emote(), message.player(), now / 1000L, NexoEmoteAccount.TICKET_KEYS)
					? NexoEmoteAccount.animation(message.emote()) : null;
		}
		for (NexoEmotes.Emote starter : NexoEmotes.STARTERS) {
			if (starter.id().equals(message.emote())) {
				return starter.animation();
			}
		}
		return null;
	}

	/** Collects a frame that may arrive in pieces, and hands it to the game thread. */
	private final class Receiver implements WebSocket.Listener {
		private final byte[] frameKey;
		private final ByteArrayOutputStream partial = new ByteArrayOutputStream();

		Receiver(byte[] frameKey) {
			this.frameKey = frameKey;
		}

		@Override
		public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
			byte[] chunk = new byte[data.remaining()];
			data.get(chunk);
			if (partial.size() + chunk.length <= MAX_FRAME) {
				partial.writeBytes(chunk);
			} else {
				partial.reset();
				partial.write(0);
			}
			if (last) {
				byte[] frame = partial.toByteArray();
				partial.reset();
				// Decrypted here, not on the game thread, and dropped past a cap: a flood must not stall a frame.
				NexoEmoteWire.Message message = NexoEmoteWire.decode(frame, frameKey);
				if (message != null && pending.incrementAndGet() <= MAX_PENDING) {
					Minecraft.getInstance().execute(() -> {
						pending.decrementAndGet();
						receive(message, frameKey);
					});
				} else if (message != null) {
					pending.decrementAndGet();
				}
			}
			webSocket.request(1);
			return CompletableFuture.completedFuture(null);
		}

		@Override
		public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
			Minecraft.getInstance().execute(() -> {
				if (socket == webSocket) {
					failed();
				}
			});
			return null;
		}

		@Override
		public void onError(WebSocket webSocket, Throwable error) {
			Minecraft.getInstance().execute(() -> {
				if (socket == webSocket) {
					failed();
				}
			});
		}
	}
}
