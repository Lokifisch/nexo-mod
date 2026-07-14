package dev.nexoclient.nexomod.auth;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;

import net.minecraft.util.Util;

/**
 * Microsoft OAuth2 sign-in via the authorization-code flow: opens the
 * user's browser to Microsoft's login page, catches the redirect on a
 * short-lived local HTTP server, then exchanges the code for tokens ->
 * Xbox Live -> XSTS -> Minecraft Services -> profile.
 *
 * <p>Ported/adapted from axieum/authme (MIT licensed,
 * github.com/axieum/authme, common/src/main/java/me/axieum/mcmod/authme/
 * api/util/MicrosoftUtils.java) — see THIRD-PARTY-NOTICES.md. Reuses
 * authme's own registered Azure AD application id, which is already
 * configured for this exact flow (public client, "personal Microsoft
 * accounts only", redirect URI http://localhost:25585/callback) — so
 * unlike the previous device-code implementation, sign-in works without
 * requiring a self-registered Azure app. Not derived from Essential-Mod.
 */
public final class MicrosoftAuth {
	private static final Logger LOGGER = LoggerFactory.getLogger("nexomod/auth");
	private static final HttpClient HTTP = HttpClient.newHttpClient();
	private static final SecureRandom SECURE_RANDOM = new SecureRandom();

	/** axieum/authme's public Azure AD application id — see class javadoc. */
	private static final String CLIENT_ID = "e16699bb-2aa8-46da-b5e3-45cbcce29091";
	private static final String SCOPE = "XboxLive.signin offline_access";
	/** Must match the redirect URI registered against CLIENT_ID in Azure. */
	private static final int CALLBACK_PORT = 25585;
	private static final String REDIRECT_URI = "http://localhost:" + CALLBACK_PORT + "/callback";

	private static final String AUTHORIZE_URL = "https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize";
	private static final String TOKEN_URL = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token";
	private static final String XBOX_LIVE_AUTH_URL = "https://user.auth.xboxlive.com/user/authenticate";
	private static final String XSTS_AUTH_URL = "https://xsts.auth.xboxlive.com/xsts/authorize";
	private static final String MINECRAFT_LOGIN_URL = "https://api.minecraftservices.com/authentication/login_with_xbox";
	private static final String MINECRAFT_PROFILE_URL = "https://api.minecraftservices.com/minecraft/profile";

	private MicrosoftAuth() {}

	public static class AuthException extends RuntimeException {
		public AuthException(String message) {
			super(message);
		}

		public AuthException(String message, Throwable cause) {
			super(message, cause);
		}
	}

	/**
	 * Starts the login flow: opens the system browser to Microsoft's sign-in
	 * page and waits for the redirect back to a local callback server.
	 * Cancel by setting {@code cancelled} to true.
	 */
	public static CompletableFuture<MinecraftAccount> login(AtomicBoolean cancelled) {
		return CompletableFuture.supplyAsync(() -> {
			String authCode = acquireAuthCode(cancelled);
			MicrosoftToken msaToken = exchangeAuthCode(authCode);
			return finishLogin(msaToken);
		});
	}

	/** Refreshes an account's Minecraft session using its stored Microsoft refresh token, without any user interaction. */
	public static MinecraftAccount refresh(MinecraftAccount account) {
		MicrosoftToken msaToken = refreshMicrosoftToken(account.microsoftRefreshToken());
		return finishLogin(msaToken);
	}

	private static MinecraftAccount finishLogin(MicrosoftToken msaToken) {
		XboxToken xblToken = authenticateXboxLive(msaToken.accessToken());
		XboxToken xstsToken = authenticateXsts(xblToken.token());
		String mcAccessToken = authenticateMinecraft(xstsToken.userhash(), xstsToken.token());
		MinecraftProfile profile = fetchProfile(mcAccessToken);

		return new MinecraftAccount(
				profile.name(),
				profile.id(),
				mcAccessToken,
				msaToken.refreshToken(),
				Instant.now().plusSeconds(msaToken.expiresInSeconds()),
				false);
	}

	private record MicrosoftToken(String accessToken, String refreshToken, int expiresInSeconds) {}
	private record XboxToken(String token, String userhash) {}
	private record MinecraftProfile(String name, java.util.UUID id) {}

	/** Opens the system browser to Microsoft's login and blocks until the OAuth redirect lands on a local callback server. */
	private static String acquireAuthCode(AtomicBoolean cancelled) {
		String state = generateState();
		CountDownLatch latch = new CountDownLatch(1);
		AtomicReference<String> authCode = new AtomicReference<>();
		AtomicReference<String> errorMessage = new AtomicReference<>();

		HttpServer server;
		try {
			server = HttpServer.create(new InetSocketAddress("localhost", CALLBACK_PORT), 0);
		} catch (IOException e) {
			throw new AuthException("Couldn't start the local sign-in callback server on port " + CALLBACK_PORT, e);
		}

		server.createContext("/callback", exchange -> {
			Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());

			if (!state.equals(query.get("state"))) {
				errorMessage.set("Sign-in response didn't match the request — try again");
			} else if (query.containsKey("code")) {
				authCode.set(query.get("code"));
			} else if (query.containsKey("error")) {
				errorMessage.set(query.getOrDefault("error_description", query.get("error")));
			}

			byte[] response = (errorMessage.get() == null
					? "Signed in! You can close this tab and return to Minecraft."
					: "Sign-in failed: " + errorMessage.get()).getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, response.length);
			try (OutputStream body = exchange.getResponseBody()) {
				body.write(response);
			}
			latch.countDown();
		});

		try {
			server.start();
			String authorizeUrl = AUTHORIZE_URL
					+ "?client_id=" + urlEncode(CLIENT_ID)
					+ "&response_type=code"
					+ "&redirect_uri=" + urlEncode(REDIRECT_URI)
					+ "&scope=" + urlEncode(SCOPE)
					+ "&state=" + urlEncode(state);
			LOGGER.info("Opening browser for Microsoft sign-in");
			Util.getPlatform().openUri(URI.create(authorizeUrl));

			while (!latch.await(500, java.util.concurrent.TimeUnit.MILLISECONDS)) {
				if (cancelled.get()) {
					throw new AuthException("Login cancelled");
				}
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AuthException("Login interrupted", e);
		} finally {
			server.stop(0);
		}

		if (cancelled.get()) {
			throw new AuthException("Login cancelled");
		}
		String error = errorMessage.get();
		if (error != null) {
			throw new AuthException(error);
		}
		String code = authCode.get();
		if (code == null || code.isBlank()) {
			throw new AuthException("Microsoft sign-in didn't return an authorization code");
		}
		return code;
	}

	private static MicrosoftToken exchangeAuthCode(String authCode) {
		JsonObject body = postForm(TOKEN_URL, Map.of(
				"client_id", CLIENT_ID,
				"grant_type", "authorization_code",
				"code", authCode,
				"redirect_uri", REDIRECT_URI));
		return new MicrosoftToken(
				body.get("access_token").getAsString(),
				body.get("refresh_token").getAsString(),
				body.get("expires_in").getAsInt());
	}

	private static MicrosoftToken refreshMicrosoftToken(String refreshToken) {
		JsonObject body = postForm(TOKEN_URL, Map.of(
				"client_id", CLIENT_ID,
				"grant_type", "refresh_token",
				"refresh_token", refreshToken,
				"scope", SCOPE));
		return new MicrosoftToken(
				body.get("access_token").getAsString(),
				body.get("refresh_token").getAsString(),
				body.get("expires_in").getAsInt());
	}

	private static XboxToken authenticateXboxLive(String msaAccessToken) {
		JsonObject requestBody = new JsonObject();
		JsonObject properties = new JsonObject();
		properties.addProperty("AuthMethod", "RPS");
		properties.addProperty("SiteName", "user.auth.xboxlive.com");
		properties.addProperty("RpsTicket", "d=" + msaAccessToken);
		requestBody.add("Properties", properties);
		requestBody.addProperty("RelyingParty", "http://auth.xboxlive.com");
		requestBody.addProperty("TokenType", "JWT");

		JsonObject response = postJson(XBOX_LIVE_AUTH_URL, requestBody);
		return extractXboxToken(response);
	}

	private static XboxToken authenticateXsts(String xblToken) {
		JsonObject requestBody = new JsonObject();
		JsonObject properties = new JsonObject();
		properties.addProperty("SandboxId", "RETAIL");
		com.google.gson.JsonArray userTokens = new com.google.gson.JsonArray();
		userTokens.add(xblToken);
		properties.add("UserTokens", userTokens);
		requestBody.add("Properties", properties);
		requestBody.addProperty("RelyingParty", "rp://api.minecraftservices.com/");
		requestBody.addProperty("TokenType", "JWT");

		HttpResponse<String> response = postJsonRaw(XSTS_AUTH_URL, requestBody);
		if (response.statusCode() == 401) {
			throw new AuthException("This Microsoft account can't be used with Minecraft (no Xbox profile, under 18 without a family group, or in a region where Xbox Live isn't available)");
		}
		return extractXboxToken(JsonParser.parseString(response.body()).getAsJsonObject());
	}

	private static XboxToken extractXboxToken(JsonObject response) {
		String token = response.get("Token").getAsString();
		String userhash = response.getAsJsonObject("DisplayClaims")
				.getAsJsonArray("xui")
				.get(0).getAsJsonObject()
				.get("uhs").getAsString();
		return new XboxToken(token, userhash);
	}

	private static String authenticateMinecraft(String userhash, String xstsToken) {
		JsonObject requestBody = new JsonObject();
		requestBody.addProperty("identityToken", "XBL3.0 x=" + userhash + ";" + xstsToken);

		JsonObject response = postJson(MINECRAFT_LOGIN_URL, requestBody);
		return response.get("access_token").getAsString();
	}

	private static MinecraftProfile fetchProfile(String mcAccessToken) {
		HttpRequest request = HttpRequest.newBuilder(URI.create(MINECRAFT_PROFILE_URL))
				.header("Authorization", "Bearer " + mcAccessToken)
				.GET()
				.build();
		HttpResponse<String> response = send(request);
		if (response.statusCode() == 404) {
			throw new AuthException("This Microsoft account doesn't own Minecraft");
		}
		JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
		String rawId = body.get("id").getAsString();
		java.util.UUID uuid = java.util.UUID.fromString(
				rawId.replaceFirst("(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5"));
		return new MinecraftProfile(body.get("name").getAsString(), uuid);
	}

	private static String generateState() {
		byte[] randomBytes = new byte[16];
		SECURE_RANDOM.nextBytes(randomBytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
	}

	private static Map<String, String> parseQuery(String rawQuery) {
		if (rawQuery == null || rawQuery.isBlank()) {
			return Map.of();
		}
		return java.util.Arrays.stream(rawQuery.split("&"))
				.map(pair -> pair.split("=", 2))
				.collect(Collectors.toMap(
						pair -> java.net.URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
						pair -> pair.length > 1 ? java.net.URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "",
						(a, b) -> a));
	}

	private static String urlEncode(String value) {
		return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	private static JsonObject postForm(String url, Map<String, String> form) {
		HttpResponse<String> response = postFormRaw(url, form);
		if (response.statusCode() / 100 != 2) {
			throw new AuthException("Microsoft sign-in request to " + url + " failed with status " + response.statusCode());
		}
		return JsonParser.parseString(response.body()).getAsJsonObject();
	}

	private static HttpResponse<String> postFormRaw(String url, Map<String, String> form) {
		String encoded = form.entrySet().stream()
				.map(e -> urlEncode(e.getKey()) + "=" + urlEncode(e.getValue()))
				.collect(Collectors.joining("&"));
		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
				.header("Content-Type", "application/x-www-form-urlencoded")
				.header("Accept", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(encoded))
				.build();
		return send(request);
	}

	private static JsonObject postJson(String url, JsonObject body) {
		HttpResponse<String> response = postJsonRaw(url, body);
		if (response.statusCode() / 100 != 2) {
			throw new AuthException("Sign-in request to " + url + " failed with status " + response.statusCode() + ": " + response.body());
		}
		return JsonParser.parseString(response.body()).getAsJsonObject();
	}

	private static HttpResponse<String> postJsonRaw(String url, JsonObject body) {
		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
				.header("Content-Type", "application/json")
				.header("Accept", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body.toString()))
				.build();
		return send(request);
	}

	private static HttpResponse<String> send(HttpRequest request) {
		try {
			return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
		} catch (IOException | InterruptedException e) {
			if (e instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
			LOGGER.error("Sign-in HTTP request to {} failed", request.uri(), e);
			throw new AuthException("Network error during sign-in", e);
		}
	}
}
