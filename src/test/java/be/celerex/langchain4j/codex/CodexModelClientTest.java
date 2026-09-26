package be.celerex.langchain4j.codex;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CodexModelClientTest {
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
	void sendsAuthenticatedRequestAndParsesCurrentCatalogMetadata() throws Exception {
		server.enqueue(
			new MockResponse()
				.addHeader("ETag", "\"catalog-1\"")
				.setBody(
					"{\"models\":[{\"slug\":\"gpt-6-astra\",\"display_name\":\"GPT-6 Astra\",\"description\":\"Agent model\",\"context_window\":272000,\"supported_in_api\":true,\"visibility\":\"list\",\"default_reasoning_level\":\"high\",\"supported_reasoning_levels\":[{\"effort\":\"low\"},{\"effort\":\"max\"},{\"effort\":\"ultra\"},{\"effort\":\"future-value\"}],\"default_reasoning_summary\":\"auto\",\"input_modalities\":[\"text\",\"image\"],\"priority\":1}]}"
				)
		);
		CodexModelCatalog catalog = client().listModels();
		var request = server.takeRequest();
		assertTrue(request.getPath().startsWith("/models?client_version="));
		assertEquals("Bearer access", request.getHeader("Authorization"));
		assertEquals("acct", request.getHeader("chatgpt-account-id"));
		assertEquals("\"catalog-1\"", catalog.etag());
		CodexModel model = catalog.apiVisibleModels().getFirst();
		assertEquals("gpt-6-astra", model.slug());
		assertEquals(272000L, model.contextWindow());
		assertEquals(
			java.util.List.of(CodexReasoningEffort.LOW, CodexReasoningEffort.MAX, CodexReasoningEffort.ULTRA),
			model.supportedReasoningEfforts()
		);
		assertTrue(model.supportsImages());
	}

	@Test
	void cachesWithEtagAndReturnsCatalogOnNotModified() throws Exception {
		server.enqueue(new MockResponse().addHeader("ETag", "tag").setBody("{\"models\":[]}"));
		server.enqueue(new MockResponse().setResponseCode(304));
		CodexModelClient client = client();
		assertFalse(client.listModels().notModified());
		CodexModelCatalog cached = client.listModels();
		assertTrue(cached.notModified());
		server.takeRequest();
		assertEquals("tag", server.takeRequest().getHeader("If-None-Match"));
	}

	@Test
	void refreshesUnauthorizedDiscoveryAndPersistsRotation() throws Exception {
		server.enqueue(new MockResponse().setResponseCode(401));
		server.enqueue(
			new MockResponse().setBody("{\"access_token\":\"new\",\"refresh_token\":\"rotated\",\"expires_in\":3600}")
		);
		server.enqueue(new MockResponse().setBody("{\"models\":[]}"));
		AtomicReference<CodexCredentials> saved = new AtomicReference<>();
		CodexSession session = CodexSession.builder()
			.credentials(credentials())
			.endpoint(server.url("responses").toString())
			.refreshEndpoint(server.url("oauth/token").toString())
			.credentialPersistence(saved::set)
			.build();
		CodexModelClient.builder()
			.session(session)
			.endpoint(server.url("models").toString())
			.clientVersion("test")
			.build()
			.listModels();
		assertEquals("/models?client_version=test", server.takeRequest().getPath());
		assertEquals("/oauth/token", server.takeRequest().getPath());
		assertEquals("Bearer new", server.takeRequest().getHeader("Authorization"));
		assertEquals("rotated", saved.get().refreshToken());
	}

	@Test
	void rejectsMalformedAndOversizedCatalogs() {
		server.enqueue(new MockResponse().setBody("{\"models\":{}}"));
		assertThrows(IllegalStateException.class, () -> client().listModels());
		server.enqueue(new MockResponse().setBody("{\"models\":[]}"));
		CodexModelClient limited = CodexModelClient.builder()
			.credentials(credentials())
			.endpoint(server.url("models").toString())
			.clientVersion("test")
			.maxBodyBytes(4)
			.build();
		assertThrows(IllegalStateException.class, limited::listModels);
	}

	@Test
	void reasoningSettingsUseExactWireValues() throws Exception {
		String payload = CodexRequestMapper.request(
			dev.langchain4j.model.chat.request.ChatRequest
				.builder()
				.messages(java.util.List.of(dev.langchain4j.data.message.UserMessage.from("x")))
				.build(),
			true,
			CodexReasoningEffort.PERSISTENT,
			CodexReasoningSummary.DETAILED
		);
		var root = CodexCredentials.JSON.readTree(payload);
		assertEquals("persistent", root.at("/reasoning/effort").asText());
		assertEquals("detailed", root.at("/reasoning/summary").asText());
	}

	private CodexModelClient client() {
		return CodexModelClient.builder()
			.credentials(credentials())
			.endpoint(server.url("models").toString())
			.clientVersion("test-version")
			.build();
	}

	private static CodexCredentials credentials() {
		return new CodexCredentials("access", "refresh", "acct", Instant.now().plusSeconds(3600));
	}
}
