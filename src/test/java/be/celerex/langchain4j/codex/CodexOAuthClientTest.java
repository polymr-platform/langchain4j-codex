package be.celerex.langchain4j.codex;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CodexOAuthClientTest {
	private MockWebServer server;

	@BeforeEach
	void start() throws Exception {
		server = new MockWebServer();
		server.start();
	}

	@AfterEach
	void stop() throws Exception {
		server.shutdown();
	}

	@Test
	void startsWithUpstreamAuthorizationParametersAndPkce() throws Exception {
		CodexOAuthAttempt attempt = client(null).start(URI.create("https://app.example.test/oauth/callback"));
		Map<String, String> query = query(attempt.authorizationUri());
		assertEquals("code", query.get("response_type"));
		assertEquals(CodexSession.DEFAULT_CLIENT_ID, query.get("client_id"));
		assertEquals("https://app.example.test/oauth/callback", query.get("redirect_uri"));
		assertEquals("S256", query.get("code_challenge_method"));
		assertEquals(attempt.state(), query.get("state"));
		assertEquals(CodexOAuthClient.DEFAULT_SCOPE, query.get("scope"));
		assertEquals("true", query.get("id_token_add_organizations"));
		assertEquals("true", query.get("codex_cli_simplified_flow"));
		assertEquals("web-app", query.get("originator"));
		assertEquals(43, attempt.state().length());
		assertFalse(attempt.toString().contains(attempt.state()));
		assertEquals(43, query.get("code_challenge").length());
	}

	@Test
	void completesFormExchangeAndPersistsCodexAuthDocument() throws Exception {
		String idToken = jwt("{\"exp\":4102444800,\"https://api.openai.com/auth\":{\"chatgpt_account_id\":\"acct-nested\"}}");
		server.enqueue(
			new MockResponse().setBody(
				"{\"id_token\":\"" + idToken
					+ "\",\"access_token\":\"access\",\"refresh_token\":\"refresh\",\"expires_in\":3600}"
			)
		);
		AtomicReference<CodexCredentials> persisted = new AtomicReference<>();
		CodexOAuthClient client = client(persisted::set);
		CodexOAuthAttempt attempt = client.start(URI.create("https://app.example.test/callback"));
		CodexCredentials credentials = client.complete(attempt, URI.create("https://app.example.test/callback?code=ignored&state=" + attempt.state()));
		RecordedRequest request = server.takeRequest();
		assertEquals("/oauth/token", request.getPath());
		assertEquals("application/x-www-form-urlencoded", request.getHeader("Content-Type"));
		Map<String, String> form = query(URI.create("http://local/?" + request.getBody().readUtf8()));
		assertEquals("authorization_code", form.get("grant_type"));
		assertEquals(CodexSession.DEFAULT_CLIENT_ID, form.get("client_id"));
		assertEquals("ignored", form.get("code"));
		assertEquals("https://app.example.test/callback", form.get("redirect_uri"));
		assertNotNull(form.get("code_verifier"));
		assertEquals(query(attempt.authorizationUri()).get("code_challenge"), challenge(form.get("code_verifier")));
		assertEquals("acct-nested", credentials.accountId());
		assertSame(credentials, persisted.get());
		JsonNode saved = new ObjectMapper().readTree(credentials.authJson());
		assertEquals("chatgpt", saved.path("auth_mode").asText());
		assertEquals(idToken, saved.at("/tokens/id_token").asText());
		assertTrue(saved.hasNonNull("last_refresh"));
	}

	@Test
	void rejectsStateMismatchBeforeTokenCall() {
		CodexOAuthClient client = client(null);
		CodexOAuthAttempt attempt = client.start(URI.create("https://app.example.test/callback"));
		assertThrows(
			CodexAuthenticationException.class,
			() -> client.complete(attempt, URI.create("https://app.example.test/callback?code=value&state=wrong"))
		);
		assertEquals(0, server.getRequestCount());
	}

	@Test
	void rejectsProviderErrorAndSingleUse() {
		CodexOAuthClient client = client(null);
		CodexOAuthAttempt errorAttempt = client.start(URI.create("https://app.example.test/callback"));
		assertThrows(
			CodexAuthenticationException.class,
			() -> client.complete(
				errorAttempt,
				URI.create("https://app.example.test/callback?error=access_denied&state=" + errorAttempt.state())
			)
		);
		CodexOAuthAttempt attempt = client.start(URI.create("https://app.example.test/callback"));
		assertThrows(CodexAuthenticationException.class, () -> client.complete(attempt, "", attempt.state()));
		assertThrows(CodexAuthenticationException.class, () -> client.complete(attempt, "code", attempt.state()));
		assertEquals(0, server.getRequestCount());
	}

	@Test
	void rejectsDuplicateCallbackParameters() {
		CodexOAuthClient client = client(null);
		CodexOAuthAttempt attempt = client.start(URI.create("https://app.example.test/callback"));
		assertThrows(
			CodexAuthenticationException.class,
			() -> client.complete(
				attempt,
				URI.create("https://app.example.test/callback?state=" + attempt.state() + "&state=x&code=value")
			)
		);
		assertEquals(0, server.getRequestCount());
	}

	private CodexOAuthClient client(CredentialPersistence persistence) {
		return CodexOAuthClient.builder()
			.httpClient(HttpClient.newHttpClient())
			.issuer(server.url("").toString())
			.originator("web-app")
			.credentialPersistence(persistence)
			.build();
	}

	private static Map<String, String> query(URI uri) {
		Map<String, String> values = new LinkedHashMap<>();
		for (String pair : uri.getRawQuery()
			.split("&")) {
			String[] entry = pair.split("=", 2);
			values.put(
				URLDecoder.decode(entry[0], StandardCharsets.UTF_8),
				URLDecoder.decode(entry[1], StandardCharsets.UTF_8)
			);
		}
		return values;
	}

	private static String challenge(String verifier) throws Exception {
		return Base64.getUrlEncoder()
			.withoutPadding()
			.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
	}

	private static String jwt(String claims) {
		return "x."
			+ Base64.getUrlEncoder()
				.withoutPadding()
				.encodeToString(claims.getBytes(StandardCharsets.UTF_8))
			+ ".z";
	}
}
