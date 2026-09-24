package be.celerex.langchain4j.codex;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import okhttp3.mockwebserver.*;
import org.junit.jupiter.api.*;
import java.time.Instant;
import java.util.concurrent.atomic.*;

class CodexModelsTest {
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
	void importsJwtAndMapsSyncRequest() throws Exception {
		String jwt = "x."
			+ java.util.Base64
				.getUrlEncoder()
				.withoutPadding()
				.encodeToString("{\"exp\":4102444800,\"https://api.openai.com/auth.chatgpt_account_id\":\"acct-jwt\"}".getBytes())
			+ ".z";
		server.enqueue(
			new MockResponse().setBody(
				"{\"id\":\"r1\",\"model\":\"gpt\",\"output\":[{\"type\":\"message\",\"content\":[{\"text\":\"hello\"}]}],\"usage\":{\"input_tokens\":20,\"input_tokens_details\":{\"cached_tokens\":12,\"cache_write_tokens\":3},\"output_tokens\":7,\"output_tokens_details\":{\"reasoning_tokens\":5},\"total_tokens\":27}}"
			)
		);
		CodexChatModel model = CodexChatModel.builder()
			.authJson("{\"access_token\":\"" + jwt + "\",\"refresh_token\":\"refresh\"}")
			.endpoint(server.url("backend-api/codex/responses").toString())
			.build();
		ChatResponse response = model.chat(ChatRequest.builder().modelName("gpt").messages(java.util.List.of(UserMessage.from("hi"))).build());
		assertEquals("hello", response.aiMessage().text());
		CodexTokenUsage usage = assertInstanceOf(CodexTokenUsage.class, response.tokenUsage());
		assertEquals(20, usage.inputTokenCount());
		assertEquals(7, usage.outputTokenCount());
		assertEquals(27, usage.totalTokenCount());
		assertEquals(12, usage.inputTokensDetails().cachedTokens());
		assertEquals(3, usage.inputTokensDetails().cacheWriteTokens());
		assertEquals(5, usage.outputTokensDetails().reasoningTokens());
		assertEquals(5, usage.reasoningTokenCount());
		RecordedRequest request = server.takeRequest();
		assertEquals("/backend-api/codex/responses", request.getPath());
		assertEquals("Bearer " + jwt, request.getHeader("Authorization"));
		assertEquals("acct-jwt", request.getHeader("chatgpt-account-id"));
		JsonNode body = new ObjectMapper().readTree(request.getBody().readUtf8());
		assertFalse(body.get("store").asBoolean());
		assertEquals("input_text", body.at("/input/0/content/0/type").asText());
		assertEquals("hi", body.at("/input/0/content/0/text").asText());
		assertEquals("medium", body.at("/reasoning/effort").asText());
		assertEquals("auto", body.at("/reasoning/summary").asText());
		assertEquals("reasoning.encrypted_content", body.at("/include/0").asText());
	}

	@Test
	void refreshesOn401AndPersistsRotation() throws Exception {
		server.enqueue(new MockResponse().setResponseCode(401));
		server.enqueue(
			new MockResponse().setBody("{\"access_token\":\"new-access\",\"refresh_token\":\"new-refresh\",\"expires_in\":3600}")
		);
		server.enqueue(new MockResponse().setBody("{\"output\":[],\"usage\":{}}"));
		AtomicReference<CodexCredentials> saved = new AtomicReference<>();
		CodexChatModel model = CodexChatModel.builder()
			.credentials(new CodexCredentials("old", "old-refresh", "acct", Instant.now().plusSeconds(3600)))
			.endpoint(server.url("responses").toString())
			.refreshEndpoint(server.url("oauth/token").toString())
			.credentialPersistence(saved::set)
			.build();
		model.chat(ChatRequest.builder().messages(java.util.List.of(UserMessage.from("x"))).build());
		assertEquals("/responses", server.takeRequest().getPath());
		assertEquals("/oauth/token", server.takeRequest().getPath());
		RecordedRequest replay = server.takeRequest();
		assertEquals("Bearer new-access", replay.getHeader("Authorization"));
		assertEquals("new-refresh", saved.get().refreshToken());
	}

	@Test
	void streamsDeltaAndCompletion() {
		server.enqueue(
			new MockResponse()
				.addHeader("Content-Type", "text/event-stream")
				.setBody(
					"data: {\"type\":\"response.output_text.delta\",\"delta\":\"hel\"}\n\ndata: {\"type\":\"response.completed\",\"response\":{\"output\":[{\"type\":\"message\",\"content\":[{\"text\":\"hello\"}]}],\"usage\":{}}}\n\n"
				)
		);
		AtomicReference<String> delta = new AtomicReference<>();
		AtomicReference<ChatResponse> complete = new AtomicReference<>();
		AtomicReference<Throwable> error = new AtomicReference<>();
		CodexStreamingChatModel.builder()
			.credentials(new CodexCredentials("access", "refresh", "acct", Instant.now().plusSeconds(3600)))
			.endpoint(server.url("responses").toString())
			.build()
			.chat(
				ChatRequest.builder().messages(java.util.List.of(UserMessage.from("x"))).build(),
				new StreamingChatResponseHandler() {
					public void onPartialResponse(String text) {
						delta.set(text);
					}

					public void onCompleteResponse(ChatResponse response) {
						complete.set(response);
					}

					public void onError(Throwable throwable) {
						error.set(throwable);
					}
				}
			);
		assertTimeoutPreemptively(
			java.time.Duration.ofSeconds(3),
			() -> {
				while (complete.get() == null && error.get() == null) Thread.sleep(10);
			}
		);
		assertNull(error.get());
		assertEquals("hel", delta.get());
		assertEquals("hello", complete.get().aiMessage().text());
	}

	@Test
	void streamsFunctionCallIntoTerminalResponse() {
		server.enqueue(
			new MockResponse()
				.addHeader("Content-Type", "text/event-stream")
				.setBody(
					"data: {\"type\":\"response.output_item.added\",\"item\":{\"id\":\"fc_1\",\"type\":\"function_call\",\"call_id\":\"call_1\",\"name\":\"lookup\",\"arguments\":\"\"}}\n\n"
						+ "data: {\"type\":\"response.function_call_arguments.delta\",\"item_id\":\"fc_1\",\"delta\":\"{\\\"id\\\":42}\"}\n\n"
						+ "data: {\"type\":\"response.output_item.done\",\"item\":{\"id\":\"fc_1\",\"type\":\"function_call\",\"call_id\":\"call_1\",\"name\":\"lookup\",\"arguments\":\"{\\\"id\\\":42}\"}}\n\n"
						+ "data: {\"type\":\"response.completed\",\"response\":{\"output\":[],\"usage\":{}}}\n\n"
				)
		);
		AtomicReference<ChatResponse> complete = new AtomicReference<>();
		AtomicReference<Throwable> error = new AtomicReference<>();
		AtomicReference<dev.langchain4j.model.chat.response.CompleteToolCall> completeCall = new AtomicReference<>();
		CodexStreamingChatModel.builder()
			.credentials(new CodexCredentials("access", "refresh", "acct", Instant.now().plusSeconds(3600)))
			.endpoint(server.url("responses").toString())
			.build()
			.chat(
				ChatRequest.builder().messages(java.util.List.of(UserMessage.from("use lookup"))).build(),
				new StreamingChatResponseHandler() {
					public void onCompleteToolCall(dev.langchain4j.model.chat.response.CompleteToolCall call) {
						completeCall.set(call);
					}

					public void onCompleteResponse(ChatResponse response) {
						complete.set(response);
					}

					public void onError(Throwable throwable) {
						error.set(throwable);
					}
				}
			);
		assertTimeoutPreemptively(
			java.time.Duration.ofSeconds(3),
			() -> {
				while (complete.get() == null && error.get() == null) Thread.sleep(10);
			}
		);
		assertNull(error.get());
		assertEquals("lookup", completeCall.get().toolExecutionRequest().name());
		assertEquals("{\"id\":42}", complete.get().aiMessage().toolExecutionRequests().getFirst().arguments());
	}

	@Test
	void mapsReasoningFinishReasonsAndUsageBoundaries() {
		ChatResponse response = CodexRequestMapper.response(
			"{\"status\":\"completed\",\"output\":[{\"type\":\"reasoning\",\"summary\":[{\"text\":\"summary\"}],\"content\":[{\"text\":\" details\"}]}],\"usage\":{\"input_tokens\":1,\"output_tokens\":2,\"total_tokens\":3}}"
		);
		assertEquals("summary details", response.aiMessage().thinking());
		assertEquals(dev.langchain4j.model.output.FinishReason.STOP, response.finishReason());
		ChatResponse length = CodexRequestMapper.response(
			"{\"status\":\"incomplete\",\"incomplete_details\":{\"reason\":\"max_output_tokens\"},\"output\":[],\"usage\":{}}"
		);
		assertEquals(dev.langchain4j.model.output.FinishReason.LENGTH, length.finishReason());
		assertThrows(
			IllegalArgumentException.class,
			() -> CodexRequestMapper.response("{\"output\":[],\"usage\":{\"input_tokens\":2147483648}}")
		);
	}

	@Test
	void streamsReasoningAndNestedFailureDiagnostics() {
		server.enqueue(
			new MockResponse()
				.addHeader("Content-Type", "text/event-stream")
				.setBody(
					"data: {\"type\":\"response.reasoning_summary_text.delta\",\"delta\":\"thinking\"}\n\n"
						+ "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[],\"usage\":{}}}\n\n"
				)
		);
		AtomicReference<ChatResponse> complete = new AtomicReference<>();
		AtomicReference<String> partialThinking = new AtomicReference<>();
		AtomicReference<Throwable> error = new AtomicReference<>();
		streamModel()
			.chat(
				ChatRequest.builder().messages(java.util.List.of(UserMessage.from("x"))).build(),
				new StreamingChatResponseHandler() {
					public void onPartialThinking(dev.langchain4j.model.chat.response.PartialThinking thinking) {
						partialThinking.set(thinking.text());
					}

					public void onCompleteResponse(ChatResponse response) {
						complete.set(response);
					}

					public void onError(Throwable throwable) {
						error.set(throwable);
					}
				}
			);
		assertTimeoutPreemptively(
			java.time.Duration.ofSeconds(3),
			() -> {
				while (complete.get() == null && error.get() == null) Thread.sleep(10);
			}
		);
		assertNull(error.get());
		assertEquals("thinking", partialThinking.get());
		assertEquals("thinking", complete.get().aiMessage().thinking());

		server.enqueue(
			new MockResponse()
				.addHeader("Content-Type", "text/event-stream")
				.setBody(
					"data: {\"type\":\"response.failed\",\"response\":{\"error\":{\"message\":\"context too large\"}}}\n\n"
				)
		);
		AtomicReference<Throwable> failure = new AtomicReference<>();
		streamModel()
			.chat(
				ChatRequest.builder().messages(java.util.List.of(UserMessage.from("x"))).build(),
				new StreamingChatResponseHandler() {
					public void onCompleteResponse(ChatResponse response) {}

					public void onError(Throwable throwable) {
						failure.set(throwable);
					}
				}
			);
		assertTimeoutPreemptively(java.time.Duration.ofSeconds(3), () -> {
			while (failure.get() == null) Thread.sleep(10);
		});
		assertTrue(failure.get().getMessage().contains("context too large"));
	}

	private CodexStreamingChatModel streamModel() {
		return CodexStreamingChatModel.builder()
			.credentials(new CodexCredentials("access", "refresh", "acct", Instant.now().plusSeconds(3600)))
			.endpoint(server.url("responses").toString())
			.build();
	}

	@Test
	void preservesImportedAuthDocumentAndRedactsSecrets() throws Exception {
		String jwt = "x."
			+ java.util.Base64
				.getUrlEncoder()
				.withoutPadding()
				.encodeToString("{\"exp\":4102444800,\"https://api.openai.com/auth.chatgpt_account_id\":\"acct\"}".getBytes())
			+ ".z";
		java.nio.file.Path file = java.nio.file.Files.createTempFile("codex", ".json");
		CodexCredentials credentials = CodexCredentials.fromJson(
			"{\"auth_mode\":\"chatgpt\",\"OPENAI_API_KEY\":\"api-secret\",\"tokens\":{\"id_token\":\"id\",\"access_token\":\""
				+ jwt + "\",\"refresh_token\":\"refresh-secret\",\"account_id\":\"acct\"},\"last_refresh\":\"old\"}"
		);
		new PathCredentialPersistence(file).persist(credentials);
		String persisted = java.nio.file.Files.readString(file);
		assertTrue(persisted.contains("\"auth_mode\":\"chatgpt\""));
		assertTrue(persisted.contains("\"OPENAI_API_KEY\":\"api-secret\""));
		assertTrue(persisted.contains("\"id_token\":\"id\""));
		assertFalse(credentials.toString().contains("refresh-secret"));
		assertFalse(credentials.toString().contains(jwt));
	}

	@Test
	void sendsInstructionsAndSeparateFunctionItems() throws Exception {
		server.enqueue(new MockResponse().setBody("{\"output\":[],\"usage\":{}}"));
		var request = ChatRequest.builder()
			.messages(
				java.util.List.of(
					new dev.langchain4j.data.message.SystemMessage("rules"),
					UserMessage.from("ask"),
					new dev.langchain4j.data.message.AiMessage(
						"assistant history",
						java.util.List.of(
							dev.langchain4j.agent.tool.ToolExecutionRequest
								.builder()
								.id("call-1")
								.name("lookup")
								.arguments("{}")
								.build()
						)
					),
					dev.langchain4j.data.message.ToolExecutionResultMessage.from("call-1", "lookup", "result")
				)
			)
			.build();
		CodexChatModel.builder()
			.credentials(new CodexCredentials("access", "refresh", "acct", Instant.now().plusSeconds(3600)))
			.endpoint(server.url("responses").toString())
			.build()
			.chat(request);
		JsonNode body = new ObjectMapper().readTree(server.takeRequest().getBody().readUtf8());
		assertEquals("rules", body.path("instructions").asText());
		assertEquals("message", body.at("/input/0/type").asText());
		assertEquals("input_text", body.at("/input/0/content/0/type").asText());
		assertEquals("output_text", body.at("/input/1/content/0/type").asText());
		assertEquals("function_call", body.at("/input/2/type").asText());
		assertEquals("function_call_output", body.at("/input/3/type").asText());
		assertTrue(body.path("parallel_tool_calls").isBoolean());
	}

	@Test
	void sharedSessionRefreshesOneStale401AndReplays() throws Exception {
		server.enqueue(new MockResponse().setResponseCode(401));
		server.enqueue(
			new MockResponse().setBody("{\"access_token\":\"new\",\"refresh_token\":\"rotated\",\"expires_in\":3600}")
		);
		server.enqueue(new MockResponse().setBody("{\"output\":[],\"usage\":{}}"));
		CodexSession session = CodexSession.builder()
			.credentials(new CodexCredentials("old", "refresh", "acct", Instant.now().plusSeconds(3600)))
			.endpoint(server.url("responses").toString())
			.refreshEndpoint(server.url("oauth/token").toString())
			.build();
		CodexChatModel.builder()
			.session(session)
			.build()
			.chat(ChatRequest.builder().messages(java.util.List.of(UserMessage.from("x"))).build());
		server.takeRequest();
		RecordedRequest refresh = server.takeRequest();
		RecordedRequest replay = server.takeRequest();
		assertEquals("application/json", refresh.getHeader("Content-Type"));
		assertTrue(refresh.getBody().readUtf8().contains("\"grant_type\":\"refresh_token\""));
		assertEquals("Bearer new", replay.getHeader("Authorization"));
	}

	@Test
	void mapsOrderedTextAndImagesAndRejectsUnsupportedModalities() throws Exception {
		server.enqueue(new MockResponse().setBody("{\"output\":[],\"usage\":{}}"));
		UserMessage message = UserMessage.from(
			java.util.List.of(
				dev.langchain4j.data.message.TextContent.from("before"),
				dev.langchain4j.data.message.ImageContent.from(
					java.net.URI.create("https://example.test/image.png"),
					dev.langchain4j.data.message.ImageContent.DetailLevel.HIGH
				),
				dev.langchain4j.data.message.TextContent.from("after"),
				dev.langchain4j.data.message.ImageContent.from("YWJj", "image/png")
			)
		);
		CodexChatModel.builder()
			.credentials(new CodexCredentials("access", "refresh", "acct", Instant.now().plusSeconds(3600)))
			.endpoint(server.url("responses").toString())
			.build()
			.chat(ChatRequest.builder().messages(java.util.List.of(message)).build());
		JsonNode content = new ObjectMapper().readTree(server.takeRequest().getBody().readUtf8())
			.at("/input/0/content");
		assertEquals("before", content.get(0).path("text").asText());
		assertEquals("https://example.test/image.png", content.get(1).path("image_url").asText());
		assertEquals("high", content.get(1).path("detail").asText());
		assertEquals("after", content.get(2).path("text").asText());
		assertEquals("data:image/png;base64,YWJj", content.get(3).path("image_url").asText());

		ChatRequest unsupported = ChatRequest.builder()
			.messages(
				java.util.List.of(UserMessage.from(dev.langchain4j.data.message.AudioContent.from("YQ==", "audio/wav")))
			)
			.build();
		assertThrows(IllegalArgumentException.class, () -> CodexRequestMapper.request(unsupported, true));
		ChatRequest ultra = ChatRequest.builder()
			.messages(
				java.util.List.of(
					UserMessage.from(
						dev.langchain4j.data.message.ImageContent.from("YQ==", "image/png", dev.langchain4j.data.message.ImageContent.DetailLevel.ULTRA_HIGH)
					)
				)
			)
			.build();
		assertThrows(IllegalArgumentException.class, () -> CodexRequestMapper.request(ultra, true));
	}

	@Test
	void exposesOneStreamingHandleUsesCustomExecutorAndCancels() throws Exception {
		server.enqueue(
			new MockResponse()
				.addHeader("Content-Type", "text/event-stream")
				.setBody(
					"data: {\"type\":\"response.output_text.delta\",\"delta\":\"first\"}\n\n"
						+ "data: {\"type\":\"response.output_text.delta\",\"delta\":\"second\"}\n\n"
						+ "data: {\"type\":\"response.completed\",\"response\":{\"output\":[],\"usage\":{}}}\n\n"
				)
		);
		java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> new Thread(runnable, "codex-test-executor"));
		try {
			AtomicReference<dev.langchain4j.model.chat.response.StreamingHandle> handle = new AtomicReference<>();
			AtomicReference<Throwable> error = new AtomicReference<>();
			AtomicReference<String> callbackThread = new AtomicReference<>();
			AtomicInteger partials = new AtomicInteger();
			AtomicBoolean completed = new AtomicBoolean();
			CodexStreamingChatModel.builder()
				.credentials(new CodexCredentials("access", "refresh", "acct", Instant.now().plusSeconds(3600)))
				.endpoint(server.url("responses").toString())
				.executor(executor)
				.build()
				.chat(
					ChatRequest.builder().messages(java.util.List.of(UserMessage.from("x"))).build(),
					new StreamingChatResponseHandler() {
						@Override
						public void onPartialResponse(
								dev.langchain4j.model.chat.response.PartialResponse partial,
								dev.langchain4j.model.chat.response.PartialResponseContext context) {
							handle.set(context.streamingHandle());
							callbackThread.set(Thread.currentThread().getName());
							partials.incrementAndGet();
							context.streamingHandle().cancel();
						}

						@Override
						public void onCompleteResponse(ChatResponse response) {
							completed.set(true);
						}

						@Override
						public void onError(Throwable throwable) {
							error.set(throwable);
						}
					}
				);
			assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), () -> {
				while (error.get() == null) Thread.sleep(10);
			});
			assertNotNull(handle.get());
			assertTrue(handle.get().isCancelled());
			assertInstanceOf(java.util.concurrent.CancellationException.class, error.get());
			assertTrue(callbackThread.get().startsWith("codex-test-executor"));
			assertEquals(1, partials.get());
			assertFalse(completed.get());
		}
		finally {
			executor.shutdownNow();
		}
	}
}
