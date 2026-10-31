import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import dev.nexoclient.nexomod.emotes.NexoEmoteTicket;
import dev.nexoclient.nexomod.emotes.NexoEmoteWire;

/** The service signs, this verifies; two clients talk through the real relay. */
public class Check {
	static void expect(boolean actual, String what) {
		if (!actual) {
			throw new AssertionError(what);
		}
		System.out.println("ok: " + what);
	}

	public static void main(String[] args) throws Exception {
		List<String> lines = Files.readAllLines(Path.of(args[0]));
		var keys = NexoEmoteTicket.keys(Map.of(1, lines.get(0)));
		String emote = lines.get(1);
		UUID owner = UUID.fromString(lines.get(2));
		byte[] good = java.util.HexFormat.of().parseHex(lines.get(3));
		byte[] expired = java.util.HexFormat.of().parseHex(lines.get(4));
		long now = System.currentTimeMillis() / 1000L;

		expect(NexoEmoteTicket.verify(good, emote, owner, now, keys), "the service's ticket verifies");
		expect(!NexoEmoteTicket.verify(good, emote, UUID.randomUUID(), now, keys), "another player's ticket is refused");
		expect(!NexoEmoteTicket.verify(good, "0".repeat(64), owner, now, keys), "a ticket for another emote is refused");
		expect(!NexoEmoteTicket.verify(expired, emote, owner, now, keys), "an expired ticket is refused");
		expect(!NexoEmoteTicket.verify(good, emote, owner, now, Map.of()), "an unknown key is refused");
		byte[] tampered = good.clone();
		tampered[40] ^= 1;
		expect(!NexoEmoteTicket.verify(tampered, emote, owner, now, keys), "a tampered ticket is refused");
		expect(!NexoEmoteTicket.verify(new byte[5], emote, owner, now, keys), "a short ticket is refused");

		byte[] key = NexoEmoteWire.key("Play.Example.net");
		expect(java.util.Arrays.equals(key, NexoEmoteWire.key("play.example.net:25565")), "one key per server, however it is typed");
		NexoEmoteWire.Message message = new NexoEmoteWire.Message(NexoEmoteWire.PLAY, owner, 123L, 20F, emote, good);
		byte[] frame = NexoEmoteWire.encode(message, key);
		expect(frame.length <= 400, "a message fits the relay's frame limit");
		expect(new String(frame, StandardCharsets.ISO_8859_1).indexOf(emote) < 0, "the emote id is not readable on the wire");
		NexoEmoteWire.Message back = NexoEmoteWire.decode(frame, key);
		expect(back != null && back.player().equals(owner) && back.emote().equals(emote) && back.tick() == 20F, "a message survives the round trip");
		expect(NexoEmoteWire.decode(frame, NexoEmoteWire.key("other.server")) == null, "another server's key cannot read it");
		frame[frame.length - 1] ^= 1;
		expect(NexoEmoteWire.decode(frame, key) == null, "a tampered message is refused");
		expect(NexoEmoteWire.decode(new byte[3], key) == null, "garbage is refused");

		// Two clients through the running relay.
		String url = args[1];
		byte[] room = NexoEmoteWire.room("play.example.net");
		CompletableFuture<byte[]> received = new CompletableFuture<>();
		HttpClient http = HttpClient.newHttpClient();
		WebSocket.Listener quiet = new WebSocket.Listener() {};
		WebSocket a = http.newWebSocketBuilder().buildAsync(URI.create(url), quiet).get(5, TimeUnit.SECONDS);
		WebSocket b = http.newWebSocketBuilder().buildAsync(URI.create(url), new WebSocket.Listener() {
			@Override
			public CompletionStage<?> onBinary(WebSocket socket, ByteBuffer data, boolean last) {
				byte[] bytes = new byte[data.remaining()];
				data.get(bytes);
				received.complete(bytes);
				return null;
			}
		}).get(5, TimeUnit.SECONDS);
		a.sendBinary(ByteBuffer.wrap(room), true).get(5, TimeUnit.SECONDS);
		b.sendBinary(ByteBuffer.wrap(room), true).get(5, TimeUnit.SECONDS);
		Thread.sleep(300);
		a.sendBinary(ByteBuffer.wrap(NexoEmoteWire.encode(message, key)), true).get(5, TimeUnit.SECONDS);
		NexoEmoteWire.Message relayed = NexoEmoteWire.decode(received.get(5, TimeUnit.SECONDS), key);
		expect(relayed != null && relayed.emote().equals(emote), "a message reaches the other client through the relay");
		a.abort();
		b.abort();
		System.exit(0);
	}
}
