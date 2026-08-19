package dev.nexoclient.nexomod.cosmetics;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Where the cosmetics service lives and how it is talked to.
 *
 * <p>Unlike the badge service (still on {@code lokifisch.dev} for older
 * installs, see {@code BadgeService}), cosmetics is new — there is nothing to
 * stay compatible with — so it goes straight on {@code nexomc.dev}, the
 * product's own domain.
 *
 * <p>Overridable through {@code nexomod.cosmetics.url} for a development
 * build to point at a local instance without a rebuild, same as the badge
 * service's {@code nexomod.badge.url}. Deliberately not a config-screen
 * setting, for the same reason: the only thing a player could do with that
 * field is send their identity and wallet somewhere else.
 */
final class CosmeticsServiceClient {
	static final String DEFAULT_BASE_URL = "https://nexomc.dev/nexo/cosmetics/api/v1";
	static final String USER_AGENT = "nexomod-cosmetics/1.0";
	static final Duration TIMEOUT = Duration.ofSeconds(10);

	private final String baseUrl;
	private final HttpClient http;

	CosmeticsServiceClient() {
		String configured = System.getProperty("nexomod.cosmetics.url", DEFAULT_BASE_URL).trim();
		this.baseUrl = configured.endsWith("/")
				? configured.substring(0, configured.length() - 1)
				: configured;
		this.http = HttpClient.newBuilder()
				.connectTimeout(TIMEOUT)
				.followRedirects(HttpClient.Redirect.NEVER)
				.build();
	}

	String url(String path) {
		return baseUrl + path;
	}

	HttpClient http() {
		return http;
	}
}
