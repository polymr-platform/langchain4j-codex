package be.celerex.langchain4j.codex;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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

class CodexDeviceAuthClientTest {
	private MockWebServer server;

	@BeforeEach
	void startServer() throws Exception {
		server = new MockWebServer();
		server.start();
	}

	@AfterEach
	void stopServer() throws Exception {
		server.shutdown();
	}

	@Test
	void startsAndReturnsSafeAttempt() throws Exception {
		server.enqueue(
			new MockResponse().setBody("{\"device_auth_id\":\"device-secret\",\"user_code\":\"ABCD-EFGH\",\"interval\":\"7\"}")
		);
		CodexDeviceAuthAttempt attempt = client(null).start();
		RecordedRequest request = server.takeRequest();
		assertEquals("/api/accounts/deviceauth/usercode", request.getPath());
		assertEquals(
			CodexSession.DEFAULT_CLIENT_ID,
			new ObjectMapper().readTree(request.getBody().readUtf8()).path("client_id").asText()
		);
		assertEquals(server.url("codex/device").uri(), attempt.verificationUri());
		assertEquals("ABCD-EFGH", attempt.userCode());
		assertEquals(7, attempt.interval());
		assertFalse(attempt.toString().contains("device-secret"));
		assertFalse(attempt.snapshot().toString().contains("device-secret"));
	}

	@Test
	void returnsPendingForUpstreamPendingStatuses() {
		server.enqueue(
			new MockResponse().setBody("{\"device_auth_id\":\"device\",\"user_code\":\"CODE\",\"interval\":\"1\"}")
		);
		server.enqueue(new MockResponse().setResponseCode(403));
		CodexDeviceAuthAttempt attempt = client(null).start();
		assertInstanceOf(CodexDeviceAuthPollResult.Pending.class, client(null).poll(attempt));
	}

	@Test
	void exchangesAuthorizedDeviceCodeAndPersistsCredentials() throws Exception {
		String verifier = "verifier-value";
		String challenge = challenge(verifier);
		String idToken = jwt("{\"https://api.openai.com/auth.chatgpt_account_id\":\"acct\"}");
		server.enqueue(
			new MockResponse().setBody("{\"device_auth_id\":\"device\",\"user_code\":\"CODE\",\"interval\":\"1\"}")
		);
		server.enqueue(
			new MockResponse().setBody(
				"{\"authorization_code\":\"authorization-secret\",\"code_challenge\":\"" + challenge
					+ "\",\"code_verifier\":\"" + verifier + "\"}"
			)
		);
		server.enqueue(
			new MockResponse().setBody("{\"id_token\":\"" + idToken + "\",\"access_token\":\"access\",\"refresh_token\":\"refresh\"}")
		);
		AtomicReference<CodexCredentials> persisted = new AtomicReference<>();
		CodexDeviceAuthClient client = client(persisted::set);
		CodexDeviceAuthPollResult.Complete result = assertInstanceOf(CodexDeviceAuthPollResult.Complete.class, client.poll(client.start()));
		server.takeRequest();
		RecordedRequest poll = server.takeRequest();
		assertEquals("/api/accounts/deviceauth/token", poll.getPath());
		RecordedRequest exchange = server.takeRequest();
		assertEquals("/oauth/token", exchange.getPath());
		Map<String, String> form = form(exchange.getBody().readUtf8());
		assertEquals("authorization_code", form.get("grant_type"));
		assertEquals("authorization-secret", form.get("code"));
		assertEquals(server.url("deviceauth/callback").toString(), form.get("redirect_uri"));
		assertEquals(verifier, form.get("code_verifier"));
		assertEquals("acct", result.credentials().accountId());
		assertSame(result.credentials(), persisted.get());
	}

	@Test
	void rejectsMismatchedPkceChallengeWithoutTokenExchange() {
		server.enqueue(
			new MockResponse().setBody("{\"device_auth_id\":\"device\",\"user_code\":\"CODE\",\"interval\":\"1\"}")
		);
		server.enqueue(
			new MockResponse().setBody("{\"authorization_code\":\"code\",\"code_challenge\":\"wrong\",\"code_verifier\":\"verifier\"}")
		);
		CodexDeviceAuthClient client = client(null);
		assertThrows(CodexAuthenticationException.class, () -> client.poll(client.start()));
		assertEquals(2, server.getRequestCount());
	}

	private CodexDeviceAuthClient client(CredentialPersistence persistence) {
		return CodexDeviceAuthClient.builder()
			.httpClient(HttpClient.newHttpClient())
			.issuer(server.url("").uri())
			.credentialPersistence(persistence)
			.build();
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

	private static Map<String, String> form(String body) {
		Map<String, String> result = new LinkedHashMap<>();
		for (String pair : body.split("&")) {
			String[] parts = pair.split("=", 2);
			result.put(
				URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
				URLDecoder.decode(parts[1], StandardCharsets.UTF_8)
			);
		}
		return result;
	}
}
