package be.celerex.langchain4j.codex;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;

/** Framework-neutral OpenAI Codex device-code login. */
public final class CodexDeviceAuthClient {
	public static final String DEFAULT_ISSUER = CodexOAuthClient.DEFAULT_ISSUER;
	private final HttpClient httpClient;
	private final URI issuer;
	private final String clientId;
	private final CredentialPersistence persistence;
	private final Duration timeout;

	private CodexDeviceAuthClient(Builder builder) {
		httpClient = builder.httpClient == null
			? HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build()
			: builder.httpClient;
		issuer = URI.create(builder.issuer == null ? DEFAULT_ISSUER : builder.issuer);
		clientId = builder.clientId == null ? CodexSession.DEFAULT_CLIENT_ID : builder.clientId;
		persistence = builder.persistence;
		timeout = builder.timeout == null ? Duration.ofSeconds(30) : builder.timeout;
	}

	public static Builder builder() {
		return new Builder();
	}

	public CodexDeviceAuthAttempt start() {
		try {
			HttpResponse<String> response = sendJson("api/accounts/deviceauth/usercode", Map.of("client_id", clientId));
			if (response.statusCode() / 100 != 2) {
				throw new CodexAuthenticationException("Device authorization start failed with HTTP " + response.statusCode());
			}
			JsonNode body = CodexCredentials.JSON.readTree(response.body());
			String deviceAuthId = required(body, "device_auth_id");
			String userCode = body.path("user_code").asText(body.path("usercode").asText(null));
			if (userCode == null || userCode.isBlank()) {
				throw new CodexAuthenticationException("Device authorization response omitted user code");
			}
			long interval = Long.parseLong(body.path("interval").asText("5").trim());
			return new CodexDeviceAuthAttempt(
				endpoint("codex/device"),
				userCode,
				deviceAuthId,
				Math.max(1, interval),
				Instant.now().plus(Duration.ofMinutes(15))
			);
		}
		catch (CodexAuthenticationException exception) {
			throw exception;
		}
		catch (Exception exception) {
			throw new CodexAuthenticationException("Device authorization start failed", exception);
		}
	}

	public CodexDeviceAuthPollResult poll(CodexDeviceAuthAttempt attempt) {
		if (attempt == null) {
			throw new CodexAuthenticationException("Device authorization attempt is required");
		}
		if (Instant.now().isAfter(attempt.expiresAt())) {
			throw new CodexAuthenticationException("Device authorization has expired");
		}
		try {
			HttpResponse<String> response = sendJson(
				"api/accounts/deviceauth/token",
				Map.of("device_auth_id", attempt.deviceAuthId(), "user_code", attempt.userCode())
			);
			if (response.statusCode() == 403 || response.statusCode() == 404) {
				return new CodexDeviceAuthPollResult.Pending();
			}
			if (response.statusCode() / 100 != 2) {
				throw new CodexAuthenticationException("Device authorization poll failed with HTTP " + response.statusCode());
			}
			JsonNode body = CodexCredentials.JSON.readTree(response.body());
			String verifier = required(body, "code_verifier");
			if (!MessageDigest.isEqual(
				sha256Base64Url(verifier).getBytes(StandardCharsets.US_ASCII),
				required(body, "code_challenge").getBytes(StandardCharsets.US_ASCII)
			)) {
				throw new CodexAuthenticationException("Device authorization code challenge did not match verifier");
			}
			if (!attempt.complete()) {
				throw new CodexAuthenticationException("Device authorization attempt has already been completed");
			}
			CodexCredentials credentials = exchange(required(body, "authorization_code"), verifier);
			if (persistence != null) {
				persistence.persist(credentials);
			}
			return new CodexDeviceAuthPollResult.Complete(credentials);
		}
		catch (CodexAuthenticationException exception) {
			throw exception;
		}
		catch (Exception exception) {
			throw new CodexAuthenticationException("Device authorization poll failed", exception);
		}
	}

	public CodexCredentials complete(CodexDeviceAuthAttempt attempt) {
		while (true) {
			CodexDeviceAuthPollResult result = poll(attempt);
			if (result instanceof CodexDeviceAuthPollResult.Complete complete) {
				return complete.credentials();
			}
			try {
				Thread.sleep(Duration.ofSeconds(attempt.interval()).toMillis());
			}
			catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new CodexAuthenticationException("Device authorization interrupted", exception);
			}
		}
	}

	private CodexCredentials exchange(String code, String verifier) throws Exception {
		String body = form(
			Map.of(
				"grant_type",
				"authorization_code",
				"client_id",
				clientId,
				"code",
				code,
				"redirect_uri",
				endpoint("deviceauth/callback").toString(),
				"code_verifier",
				verifier
			)
		);
		HttpResponse<String> response = httpClient.send(
			HttpRequest.newBuilder(endpoint("oauth/token"))
				.timeout(timeout)
				.header("Content-Type", "application/x-www-form-urlencoded")
				.header("Accept", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body))
				.build(),
			HttpResponse.BodyHandlers.ofString()
		);
		if (response.statusCode() / 100 != 2) {
			throw new CodexAuthenticationException("Device authorization token exchange failed with HTTP " + response.statusCode());
		}
		JsonNode tokens = CodexCredentials.JSON.readTree(response.body());
		return CodexCredentials.fromOAuthTokens(
			tokens.path("id_token").asText(null),
			tokens.path("access_token").asText(null),
			tokens.path("refresh_token").asText(null),
			tokens.path("account_id").asText(null),
			tokens.path("expires_in").canConvertToLong()
				? Instant.now().plusSeconds(tokens.path("expires_in").asLong())
				: null
		);
	}

	private HttpResponse<String> sendJson(String path, Map<String, String> body) throws Exception {
		return httpClient.send(
			HttpRequest.newBuilder(endpoint(path))
				.timeout(timeout)
				.header("Content-Type", "application/json")
				.header("Accept", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(CodexCredentials.JSON.writeValueAsString(body)))
				.build(),
			HttpResponse.BodyHandlers.ofString()
		);
	}

	private URI endpoint(String path) {
		return URI.create(issuer.toString().replaceAll("/+$", "") + "/" + path);
	}

	private static String required(JsonNode body, String name) {
		String value = body.path(name).asText(null);
		if (value == null || value.isBlank()) {
			throw new CodexAuthenticationException("Device authorization response omitted " + name);
		}
		return value;
	}

	private static String form(Map<String, String> values) {
		return values.entrySet()
			.stream()
			.map(
				e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
					+ URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8)
			)
			.collect(Collectors.joining("&"));
	}

	private static String sha256Base64Url(String value) {
		try {
			return java.util.Base64
				.getUrlEncoder()
				.withoutPadding()
				.encodeToString(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII)));
		}
		catch (Exception exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	public static final class Builder {
		private HttpClient httpClient;
		private String issuer;
		private String clientId;
		private CredentialPersistence persistence;
		private Duration timeout;

		public Builder httpClient(HttpClient value) {
			httpClient = value;
			return this;
		}

		public Builder issuer(String value) {
			issuer = value;
			return this;
		}

		public Builder issuer(URI value) {
			issuer = value.toString();
			return this;
		}

		public Builder clientId(String value) {
			clientId = value;
			return this;
		}

		public Builder credentialPersistence(CredentialPersistence value) {
			persistence = value;
			return this;
		}

		public Builder timeout(Duration value) {
			timeout = value;
			return this;
		}

		public CodexDeviceAuthClient build() {
			return new CodexDeviceAuthClient(this);
		}
	}
}
