package dev.nexoclient.nexomod.lantunnel;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Where the LAN tunnel's relay comes from. Backed by a plain
 * {@code config/nexomod-lantunnel.properties} file so swapping in a
 * self-hosted relay later is a config edit, not a code change — set
 * {@code useBroker=false} and point {@code relayHost}/{@code relayPort} at
 * your own QUIClime-compatible relay (see
 * https://github.com/vgskye/e4mc-quiclime).
 *
 * <p>Defaults to Nexo's own relay, {@code relay.nexomc.dev:25570} (QUIClime
 * over UDP, no broker). Players join {@code <words>.relay.nexomc.dev} on the
 * default TCP port 25565. Not through SRV: the client sends the SRV target as
 * the handshake hostname when joining, which loses the tunnel's name.
 *
 * <p>Files written before that still hold e4mc's defaults, because the first
 * run stores every default. Those exact values are moved to the new ones on
 * load; anything else in the file is the player's own choice and is kept.
 */
public final class LanTunnelConfig {
	private static final Logger LOGGER = LoggerFactory.getLogger("nexomod/lantunnel");
	private static final String FILE_NAME = "nexomod-lantunnel.properties";

	private static final String OLD_BROKER_URL = "https://broker.e4mc.link/getBestRelay";

	public static final LanTunnelConfig INSTANCE = load();

	public final boolean hostEnabled;
	public final boolean useBroker;
	public final String brokerUrl;
	public final String relayHost;
	public final int relayPort;

	private LanTunnelConfig(boolean hostEnabled, boolean useBroker, String brokerUrl, String relayHost, int relayPort) {
		this.hostEnabled = hostEnabled;
		this.useBroker = useBroker;
		this.brokerUrl = brokerUrl;
		this.relayHost = relayHost;
		this.relayPort = relayPort;
	}

	private static LanTunnelConfig load() {
		Path path = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
		Properties defaults = new Properties();
		defaults.setProperty("hostEnabled", "true");
		defaults.setProperty("useBroker", "false");
		defaults.setProperty("brokerUrl", OLD_BROKER_URL);
		defaults.setProperty("relayHost", "relay.nexomc.dev");
		defaults.setProperty("relayPort", "25570");

		Properties props = new Properties(defaults);
		if (Files.exists(path)) {
			try (InputStream in = Files.newInputStream(path)) {
				props.load(in);
			} catch (IOException e) {
				LOGGER.warn("Failed to read {}, using defaults", path, e);
			}
			if (isOldE4mcDefault(props)) {
				for (String key : new String[] {"useBroker", "relayHost", "relayPort"}) {
					props.setProperty(key, defaults.getProperty(key));
				}
				try (OutputStream out = Files.newOutputStream(path)) {
					props.store(out, "Nexo Mod LAN tunnel relay config. See LanTunnelConfig.java for what these mean.");
					LOGGER.info("Moved {} from e4mc's relay to relay.nexomc.dev", path);
				} catch (IOException e) {
					LOGGER.warn("Failed to rewrite {}; using the new relay anyway", path, e);
				}
			}
		} else {
			try (OutputStream out = Files.newOutputStream(path)) {
				defaults.store(out, "Nexo Mod LAN tunnel relay config. See LanTunnelConfig.java for what these mean.");
			} catch (IOException e) {
				LOGGER.warn("Failed to write default {}", path, e);
			}
		}

		return new LanTunnelConfig(
				Boolean.parseBoolean(props.getProperty("hostEnabled")),
				Boolean.parseBoolean(props.getProperty("useBroker")),
				props.getProperty("brokerUrl"),
				props.getProperty("relayHost"),
				Integer.parseInt(props.getProperty("relayPort")));
	}

	/** The exact values every pre-relay.nexomc.dev install wrote on first run. */
	static boolean isOldE4mcDefault(Properties p) {
		return "true".equals(p.getProperty("useBroker"))
				&& OLD_BROKER_URL.equals(p.getProperty("brokerUrl"))
				&& "test.e4mc.link".equals(p.getProperty("relayHost"))
				&& "25575".equals(p.getProperty("relayPort"));
	}
}
