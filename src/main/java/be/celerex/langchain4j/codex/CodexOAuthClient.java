package be.celerex.langchain4j.codex;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** Framework-neutral OpenAI OAuth authorization-code login with PKCE. */
public final class CodexOAuthClient {
	public static final String DEFAULT_ISSUER = "https://auth.openai.com";
	public static final String DEFAULT_SCOPE = "openid profile email offline_access api.connectors.read api.connectors.invoke";
	private static final SecureRandom RANDOM = new SecureRandom();
	private final HttpClient httpClient;
	private final URI issuer;
	private final String clientId;
	private final String originator;
	private final CredentialPersistence persistence;

	private CodexOAuthClient(Builder builder) {
		httpClient = builder.httpClient == null ? HttpClient.newHttpClient() : builder.httpClient;
		issuer = URI.create(builder.issuer == null ? DEFAULT_ISSUER : builder.issuer);
		clientId = builder.clientId == null ? CodexSession.DEFAULT_CLIENT_ID : builder.clientId;
		originator = builder.originator == null ? "codex_cli_rs" : builder.originator;
		persistence = builder.persistence;
	}

	public static Builder builder() {
		return new Builder();
	}

	public CodexOAuthAttempt start(URI redirectUri) {
		if (redirectUri == null || !redirectUri.isAbsolute()) {
			throw new IllegalArgumentException("OAuth redirect URI must be absolute");
		}
		String state = randomValue();
		String verifier = randomValue();
		String challenge = sha256Base64Url(verifier);
		String query = form(
			Map.of(
				"response_type",
				"code",
				"client_id",
				clientId,
				"redirect_uri",
				redirectUri.toString(),
				"code_challenge",
				challenge,
				"code_challenge_method",
				"S256",
				"state",
				state,
				"scope",
				DEFAULT_SCOPE,
				"id_token_add_organizations",
				"true",
				"codex_cli_simplified_flow",
				"true",
				"originator",
				originator
			)
		);
		return new CodexOAuthAttempt(endpoint("oauth/authorize", query), state, verifier, redirectUri);
	}

	public CodexCredentials complete(CodexOAuthAttempt attempt, URI callbackUri) {
		if (callbackUri == null) {
			throw new CodexAuthenticationException("OAuth callback URI is required");
		}
		Map<String, String> parameters = callbackParameters(callbackUri.getRawQuery());
		return complete(
			attempt,
			parameters.get("code"),
			parameters.get("state"),
			parameters.get("error"),
			parameters.get("error_description")
		);
	}

	public CodexCredentials complete(CodexOAuthAttempt attempt, String code, String state) {
		return complete(attempt, code, state, null, null);
	}

	private CodexCredentials complete(
			CodexOAuthAttempt attempt,
			String code,
			String state,
			String providerError,
			String errorDescription) {
		if (attempt == null) {
			throw new CodexAuthenticationException("OAuth attempt is required");
		}
		if (!constantTimeEquals(attempt.expectedState(), state)) {
			throw new CodexAuthenticationException("OAuth callback state did not match the login attempt");
		}
		if (!attempt.use()) {
			throw new CodexAuthenticationException("OAuth attempt has already been completed");
		}
		if (providerError != null) {
			throw new CodexAuthenticationException("OAuth provider returned " + safeProviderError(providerError, errorDescription));
		}
		if (code == null || code.isBlank()) {
			throw new CodexAuthenticationException("OAuth callback omitted authorization code");
		}
		try {
			String body = form(
				Map.of(
					"grant_type",
					"authorization_code",
					"client_id",
					clientId,
					"code",
					code,
					"redirect_uri",
					attempt.redirectUri().toString(),
					"code_verifier",
					attempt.codeVerifier()
				)
			);
			HttpResponse<String> response = httpClient.send(
				HttpRequest.newBuilder(endpoint("oauth/token", null))
					.header("Content-Type", "application/x-www-form-urlencoded")
					.header("Accept", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString(body))
					.build(),
				HttpResponse.BodyHandlers.ofString()
			);
			if (response.statusCode() / 100 != 2) {
				throw new CodexAuthenticationException("OAuth token exchange failed with HTTP " + response.statusCode());
			}
			JsonNode tokens = CodexCredentials.JSON.readTree(response.body());
			CodexCredentials credentials = CodexCredentials.fromOAuthTokens(
				tokens.path("id_token").asText(null),
				tokens.path("access_token").asText(null),
				tokens.path("refresh_token").asText(null),
				tokens.path("account_id").asText(null),
				tokens.path("expires_in").canConvertToLong()
					? Instant.now().plusSeconds(tokens.path("expires_in").asLong())
					: null
			);
			if (persistence != null) {
				persistence.persist(credentials);
			}
			return credentials;
		}
		catch (CodexAuthenticationException exception) {
			throw exception;
		}
		catch (Exception exception) {
			throw new CodexAuthenticationException("OAuth token exchange failed", exception);
		}
	}

	private URI endpoint(String path, String query) {
		String base = issuer.toString().replaceAll("/+$", "");
		return URI.create(base + "/" + path + (query == null ? "" : "?" + query));
	}

	private static Map<String, String> callbackParameters(String rawQuery) {
		Map<String, String> result = new LinkedHashMap<>();
		if (rawQuery == null || rawQuery.isBlank()) {
			return result;
		}
		for (String pair : rawQuery.split("&", -1)) {
			int index = pair.indexOf('=');
			String name = decode(index < 0 ? pair : pair.substring(0, index));
			String value = decode(index < 0 ? "" : pair.substring(index + 1));
			if (!name.equals("code")
					&& !name.equals("state")
					&& !name.equals("error")
					&& !name.equals("error_description")) {
				continue;
			}
			if (result.putIfAbsent(name, value) != null) {
				throw new CodexAuthenticationException("OAuth callback contains duplicate " + name + " parameter");
			}
		}
		return result;
	}

	private static String form(Map<String, String> values) {
		return values.entrySet()
			.stream()
			.map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
			.collect(java.util.stream.Collectors.joining("&"));
	}

	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	private static String decode(String value) {
		return URLDecoder.decode(value, StandardCharsets.UTF_8);
	}

	private static String randomValue() {
		byte[] bytes = new byte[32];
		RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	private static String sha256Base64Url(String value) {
		try {
			return Base64.getUrlEncoder()
				.withoutPadding()
				.encodeToString(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII)));
		}
		catch (Exception exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	private static boolean constantTimeEquals(String expected, String actual) {
		return actual != null
			&& MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
	}

	private static String safeProviderError(String error, String description) {
		String result = error.replaceAll("[^A-Za-z0-9._ -]", "_");
		return description == null || description.isBlank() ? result : result + " (details omitted)";
	}

	public static final class Builder {
		private HttpClient httpClient;
		private String issuer;
		private String clientId;
		private String originator;
		private CredentialPersistence persistence;

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

		public Builder originator(String value) {
			originator = value;
			return this;
		}

		public Builder credentialPersistence(CredentialPersistence value) {
			persistence = value;
			return this;
		}

		public CodexOAuthClient build() {
			return new CodexOAuthClient(this);
		}
	}
}
