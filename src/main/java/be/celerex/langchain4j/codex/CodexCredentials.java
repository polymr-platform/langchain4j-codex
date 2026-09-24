package be.celerex.langchain4j.codex;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;

/** An explicitly imported Codex auth.json document. */
public final class CodexCredentials {
	private static final System.Logger LOG = System.getLogger(CodexCredentials.class.getName());
	static final ObjectMapper JSON = new ObjectMapper();
	private final ObjectNode document;
	private final String accessToken;
	private final String refreshToken;
	private final String accountId;
	private final Instant expiresAt;

	public CodexCredentials(String accessToken, String refreshToken, String accountId, Instant expiresAt) {
		this(
			document(accessToken, refreshToken, accountId, expiresAt),
			accessToken,
			refreshToken,
			accountId,
			expiresAt
		);
	}

	private CodexCredentials(ObjectNode document, String accessToken, String refreshToken, String accountId, Instant expiresAt) {
		this.document = document;
		this.accessToken = required(accessToken, "access token");
		this.refreshToken = required(refreshToken, "refresh token");
		this.accountId = required(accountId, "account id");
		this.expiresAt = expiresAt == null ? jwtExpiry(accessToken) : expiresAt;
	}

	public static CodexCredentials fromOAuthTokens(String idToken, String accessToken, String refreshToken, String accountId, Instant expiresAt) {
		String account = accountId == null || accountId.isBlank() ? jwtAccountId(idToken) : accountId;
		if (account == null || account.isBlank()) {
			account = jwtAccountId(accessToken);
		}
		ObjectNode document = JSON.createObjectNode();
		document.put("auth_mode", "chatgpt");
		ObjectNode tokens = document.putObject("tokens");
		if (idToken != null && !idToken.isBlank()) {
			tokens.put("id_token", idToken);
		}
		tokens.put("access_token", accessToken);
		tokens.put("refresh_token", refreshToken);
		tokens.put("account_id", account);
		document.put("last_refresh", Instant.now().toString());
		return new CodexCredentials(document, accessToken, refreshToken, account, expiresAt);
	}

	public static CodexCredentials fromJson(String json) {
		try {
			JsonNode parsed = JSON.readTree(json);
			if (!(parsed instanceof ObjectNode document)) {
				throw new IllegalArgumentException("auth.json must be an object");
			}
			JsonNode tokens = document.path("tokens").isObject() ? document.path("tokens") : document;
			String access = first(tokens, "access_token", "accessToken");
			String refresh = first(tokens, "refresh_token", "refreshToken");
			String account = first(tokens, "account_id", "accountId");
			if (account == null) {
				account = jwtAccountId(access);
			}
			return new CodexCredentials(document.deepCopy(), access, refresh, account, expiry(tokens, access));
		}
		catch (Exception exception) {
			throw new CodexAuthenticationException("Invalid Codex auth.json", exception);
		}
	}

	public String accessToken() {
		return accessToken;
	}

	public String refreshToken() {
		return refreshToken;
	}

	public String accountId() {
		return accountId;
	}

	public Instant expiresAt() {
		return expiresAt;
	}

	CodexCredentials rotated(String access, String refresh, String idToken, Instant expiry) {
		ObjectNode copy = document.deepCopy();
		ObjectNode tokens = copy.path("tokens") instanceof ObjectNode nested ? nested : copy;
		putExistingOrSnake(tokens, "access_token", "accessToken", access);
		putExistingOrSnake(tokens, "refresh_token", "refreshToken", refresh);
		if (idToken != null && !idToken.isBlank()) {
			putExistingOrSnake(tokens, "id_token", "idToken", idToken);
		}
		copy.put("last_refresh", Instant.now().toString());
		return new CodexCredentials(copy, access, refresh, accountId, expiry);
	}

	/** Exports the auth.json document for encrypted application-managed persistence. */
	public String authJson() {
		try {
			return JSON.writeValueAsString(document);
		}
		catch (Exception exception) {
			throw new CodexAuthenticationException("Cannot serialize Codex credentials", exception);
		}
	}

	@Override
	public String toString() {
		return "CodexCredentials[accountId=" + accountId + ", accessToken=<redacted>, refreshToken=<redacted>]";
	}

	private static ObjectNode document(String access, String refresh, String account, Instant expiry) {
		ObjectNode result = JSON.createObjectNode();
		ObjectNode tokens = result.putObject("tokens");
		tokens.put("access_token", access);
		tokens.put("refresh_token", refresh);
		tokens.put("account_id", account);
		if (expiry != null) {
			tokens.put("expires_at", expiry.getEpochSecond());
		}
		return result;
	}

	private static void putExistingOrSnake(ObjectNode target, String snake, String camel, String value) {
		target.put(target.has(camel) ? camel : snake, value);
	}

	private static String required(String value, String name) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException("Codex " + name + " is required");
		}
		return value;
	}

	private static String first(JsonNode node, String...names) {
		for (String name : names) if (node.hasNonNull(name) && !node.get(name).asText().isBlank()) {
			return node.get(name).asText();
		}
		return null;
	}

	private static Instant expiry(JsonNode tokens, String access) {
		String value = first(tokens, "expires_at", "expiresAt", "expiry");
		if (value != null) {
			try {
				return Instant.ofEpochSecond(Long.parseLong(value));
			}
			catch (NumberFormatException exception) {
				return Instant.parse(value);
			}
		}
		return jwtExpiry(access);
	}

	private static Instant jwtExpiry(String access) {
		JsonNode claims = claims(access);
		return claims != null
				&& claims.has("exp")
			? Instant.ofEpochSecond(claims.get("exp").asLong())
			: Instant.EPOCH;
	}

	private static String jwtAccountId(String token) {
		JsonNode claims = claims(token);
		if (claims == null) {
			return null;
		}
		String account = first(claims, "https://api.openai.com/auth.chatgpt_account_id", "chatgpt_account_id", "account_id");
		if (account != null) {
			return account;
		}
		for (String namespace : new String[] { "https://api.openai.com/auth", "https://api.openai.com" }) {
			JsonNode nested = claims.path(namespace);
			if (nested.isObject()) {
				account = first(nested, "chatgpt_account_id", "account_id");
				if (account != null) {
					return account;
				}
			}
		}
		return null;
	}

	private static JsonNode claims(String token) {
		if (token == null) {
			return null;
		}
		try {
			String[] parts = token.split("\\.");
			return parts.length == 3 ? JSON.readTree(Base64.getUrlDecoder().decode(parts[1])) : null;
		}
		catch (Exception exception) {
			LOG.log(System.Logger.Level.DEBUG, "Could not parse JWT claims", exception);
			return null;
		}
	}
}
