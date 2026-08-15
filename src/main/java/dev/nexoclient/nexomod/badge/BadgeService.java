package dev.nexoclient.nexomod.badge;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Where the badge service lives and how it is talked to.
 *
 * <p>The base URL is overridable through the {@code nexomod.badge.url} system
 * property so a development build can point at a local instance without a
 * rebuild. It is deliberately not a config-screen setting: the only thing a
 * player could do with that field is send their identity somewhere else.
 */
final class BadgeService {
	static final String DEFAULT_BASE_URL = "https://lokifisch.dev/nexo/api/v1";
	static final String USER_AGENT = "nexomod-badge/1.0";
	static final Duration TIMEOUT = Duration.ofSeconds(10);

	private final String baseUrl;
	private final HttpClient http;

	BadgeService() {
		String configured = System.getProperty("nexomod.badge.url", DEFAULT_BASE_URL).trim();
		this.baseUrl = configured.endsWith("/")
				? configured.substring(0, configured.length() - 1)
				: configured;
		this.http = HttpClient.newBuilder()
				.connectTimeout(TIMEOUT)
				// The service answers 302-free; following redirects would only
				// widen where an identity proof could end up.
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
