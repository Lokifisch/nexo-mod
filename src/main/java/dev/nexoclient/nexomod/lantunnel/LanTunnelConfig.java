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
 * <p>Defaults to e4mc's own public relay/broker, same as installing the
 * real e4mc mod would use.
 */
public final class LanTunnelConfig {
	private static final Logger LOGGER = LoggerFactory.getLogger("nexomod/lantunnel");
	private static final String FILE_NAME = "nexomod-lantunnel.properties";

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
		defaults.setProperty("useBroker", "true");
		defaults.setProperty("brokerUrl", "https://broker.e4mc.link/getBestRelay");
		defaults.setProperty("relayHost", "test.e4mc.link");
		defaults.setProperty("relayPort", "25575");

		Properties props = new Properties(defaults);
		if (Files.exists(path)) {
			try (InputStream in = Files.newInputStream(path)) {
				props.load(in);
			} catch (IOException e) {
				LOGGER.warn("Failed to read {}, using defaults", path, e);
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
}
