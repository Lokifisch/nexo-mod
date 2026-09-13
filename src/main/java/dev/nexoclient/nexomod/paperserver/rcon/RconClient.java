package dev.nexoclient.nexomod.paperserver.rcon;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * A minimal Source RCON client — the protocol every Paper/vanilla server
 * already speaks (see {@code enable-rcon} in {@code server.properties}).
 * This is the one control channel for "stop this server" and "run a
 * console command," on purpose: it decouples who holds the OS process
 * handle from who can control the server, so the launcher can stop a
 * server the mod started and vice versa, without a second hand-rolled
 * cross-language IPC protocol.
 *
 * <p>Hand-rolled rather than a dependency, matching the judgement call this
 * codebase already made for Server List Ping framing: the protocol is a
 * handful of length-prefixed packets over one TCP connection, small enough
 * that a library would cost more than it saves.
 *
 * <p>Packet layout (all integers little-endian):
 * {@code length(4) | requestId(4) | type(4) | body | 0x00 | 0x00}, where
 * {@code length} counts everything after itself.
 */
public final class RconClient implements Closeable {
	private static final int TYPE_RESPONSE_VALUE = 0;
	/** SERVERDATA_AUTH_RESPONSE and SERVERDATA_EXECCOMMAND share this value; direction disambiguates them. */
	private static final int TYPE_AUTH_RESPONSE_OR_EXEC = 2;
	private static final int TYPE_AUTH = 3;
	private static final int MAX_PACKET_BYTES = 1 << 16;

	private final Socket socket;
	private final DataOutputStream out;
	private final DataInputStream in;
	private int nextId = 1;

	private RconClient(Socket socket) throws IOException {
		this.socket = socket;
		this.out = new DataOutputStream(socket.getOutputStream());
		this.in = new DataInputStream(socket.getInputStream());
	}

	/** Connects and authenticates. Throws if either fails within {@code timeout}. */
	public static RconClient connect(String host, int port, String password, Duration timeout) throws IOException {
		Socket socket = new Socket();
		socket.connect(new InetSocketAddress(host, port), (int) timeout.toMillis());
		socket.setSoTimeout((int) timeout.toMillis());
		RconClient client = new RconClient(socket);
		try {
			if (!client.authenticate(password)) {
				throw new IOException("RCON authentication rejected");
			}
		} catch (IOException | RuntimeException e) {
			client.close();
			throw e;
		}
		return client;
	}

	private boolean authenticate(String password) throws IOException {
		int id = nextId++;
		send(id, TYPE_AUTH, password);
		// Some server implementations send an empty SERVERDATA_RESPONSE_VALUE
		// ahead of the real auth response; skip anything that isn't type 2.
		while (true) {
			Packet response = receive();
			if (response.type == TYPE_AUTH_RESPONSE_OR_EXEC) {
				return response.id == id;
			}
		}
	}

	/** Runs a console command (e.g. {@code "stop"}) and returns its text output. */
	public String command(String command) throws IOException {
		int id = nextId++;
		send(id, TYPE_AUTH_RESPONSE_OR_EXEC, command);
		Packet response = receive();
		if (response.type != TYPE_RESPONSE_VALUE) {
			throw new IOException("Unexpected RCON response type " + response.type);
		}
		return response.body;
	}

	private void send(int id, int type, String body) throws IOException {
		byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
		int length = 4 + 4 + bodyBytes.length + 2;
		ByteBuffer packet = ByteBuffer.allocate(4 + length).order(ByteOrder.LITTLE_ENDIAN);
		packet.putInt(length);
		packet.putInt(id);
		packet.putInt(type);
		packet.put(bodyBytes);
		packet.put((byte) 0);
		packet.put((byte) 0);
		out.write(packet.array());
		out.flush();
	}

	private Packet receive() throws IOException {
		byte[] lengthBytes = new byte[4];
		in.readFully(lengthBytes);
		int length = ByteBuffer.wrap(lengthBytes).order(ByteOrder.LITTLE_ENDIAN).getInt();
		if (length < 10 || length > MAX_PACKET_BYTES) {
			throw new IOException("RCON packet length out of range: " + length);
		}

		byte[] rest = new byte[length];
		in.readFully(rest);
		ByteBuffer buf = ByteBuffer.wrap(rest).order(ByteOrder.LITTLE_ENDIAN);
		int id = buf.getInt();
		int type = buf.getInt();
		byte[] bodyBytes = new byte[length - 4 - 4 - 2];
		buf.get(bodyBytes);
		// The trailing two null bytes are left unread by design — bodyBytes already excludes them.
		return new Packet(id, type, new String(bodyBytes, StandardCharsets.UTF_8));
	}

	@Override
	public void close() throws IOException {
		socket.close();
	}

	private record Packet(int id, int type, String body) {}
}
