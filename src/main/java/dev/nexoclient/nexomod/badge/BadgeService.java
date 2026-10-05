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
		if (!isSecure(configured)) {
			// The access-token proof is bound to this host; never send it over cleartext.
			org.slf4j.LoggerFactory.getLogger("nexomod/badge")
					.warn("Ignoring nexomod.badge.url: only https (or loopback http) is allowed; using the default");
			configured = DEFAULT_BASE_URL;
		}
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

	/** https only; plain http is tolerated solely for a loopback dev instance. */
	private static boolean isSecure(String url) {
		try {
			java.net.URI uri = java.net.URI.create(url);
			if ("https".equalsIgnoreCase(uri.getScheme())) {
				return true;
			}
			String host = uri.getHost();
			return "http".equalsIgnoreCase(uri.getScheme())
					&& ("localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host));
		} catch (IllegalArgumentException e) {
			return false;
		}
	}

	String url(String path) {
		return baseUrl + path;
	}

	HttpClient http() {
		return http;
	}
}
