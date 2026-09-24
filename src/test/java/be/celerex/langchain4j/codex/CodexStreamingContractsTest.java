package be.celerex.langchain4j.codex;

import static org.junit.jupiter.api.Assertions.*;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CodexStreamingContractsTest {
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
	void asynchronouslyRefreshes401OncePersistsRotationAndReplaysSse() throws Exception {
		server.enqueue(new MockResponse().setResponseCode(401));
		server.enqueue(
			new MockResponse().setBody("{\"access_token\":\"new\",\"refresh_token\":\"rotated\",\"expires_in\":3600}")
		);
		server.enqueue(sse("data: {\"type\":\"response.completed\",\"response\":{\"output\":[],\"usage\":{}}}\n\n"));
		AtomicReference<CodexCredentials> persisted = new AtomicReference<>();
		AtomicReference<Throwable> failure = new AtomicReference<>();
		CountDownLatch completed = new CountDownLatch(1);
		model(new CodexCredentials("old", "refresh", "acct", Instant.now().plusSeconds(3600)), persisted::set)
			.chat(request(), handler(completed, failure));
		assertTrue(completed.await(3, TimeUnit.SECONDS));
		assertNull(failure.get());
		assertEquals("/responses", server.takeRequest().getPath());
		assertEquals("/oauth/token", server.takeRequest().getPath());
		RecordedRequest replay = server.takeRequest();
		assertEquals("Bearer new", replay.getHeader("Authorization"));
		assertEquals("rotated", persisted.get().refreshToken());
	}

	@Test
	void ignoresSseMetadataConcatenatesDataAndDoesNotTreatDoneAsCompletion() throws Exception {
		server.enqueue(
			sse(
				": keepalive\r\nevent: response\r\nid: 1\r\ndata: {\"type\":\"response.output_text.delta\",\r\ndata: \"delta\":\"hello\"}\r\n\r\ndata: [DONE]\r\n\r\n"
			)
		);
		AtomicReference<String> text = new AtomicReference<>();
		AtomicReference<Throwable> failure = new AtomicReference<>();
		CountDownLatch terminal = new CountDownLatch(1);
		model()
			.chat(
				request(),
				new StreamingChatResponseHandler() {
					@Override
					public void onPartialResponse(String value) {
						text.set(value);
					}

					@Override
					public void onCompleteResponse(ChatResponse response) {
						terminal.countDown();
					}

					@Override
					public void onError(Throwable throwable) {
						failure.set(throwable);
						terminal.countDown();
					}
				}
			);
		assertTrue(terminal.await(3, TimeUnit.SECONDS));
		assertEquals("hello", text.get());
		assertNotNull(failure.get());
		assertTrue(failure.get().getMessage().contains("before a terminal"));
	}

	@Test
	void emitsParallelToolCallsOnceWhenDeltaPrecedesAddedAndDoneIsDuplicated() throws Exception {
		server.enqueue(
			sse(
				"data: {\"type\":\"response.function_call_arguments.delta\",\"item_id\":\"one\",\"call_id\":\"call-one\",\"delta\":\"{\"}\n\n"
					+ "data: {\"type\":\"response.output_item.added\",\"item\":{\"id\":\"one\",\"type\":\"function_call\",\"call_id\":\"call-one\",\"name\":\"first\"}}\n\n"
					+ "data: {\"type\":\"response.output_item.done\",\"item\":{\"id\":\"one\",\"type\":\"function_call\",\"call_id\":\"call-one\",\"name\":\"first\",\"arguments\":\"{}\"}}\n\n"
					+ "data: {\"type\":\"response.output_item.done\",\"item\":{\"id\":\"one\",\"type\":\"function_call\",\"call_id\":\"call-one\",\"name\":\"first\",\"arguments\":\"{}\"}}\n\n"
					+ "data: {\"type\":\"response.output_item.done\",\"item\":{\"id\":\"two\",\"type\":\"function_call\",\"call_id\":\"call-two\",\"name\":\"second\",\"arguments\":\"[]\"}}\n\n"
					+ "data: {\"type\":\"response.completed\",\"response\":{\"output\":[],\"usage\":{}}}\n\n"
			)
		);
		AtomicInteger callCount = new AtomicInteger();
		AtomicReference<ChatResponse> response = new AtomicReference<>();
		AtomicReference<Throwable> failure = new AtomicReference<>();
		CountDownLatch terminal = new CountDownLatch(1);
		model()
			.chat(
				request(),
				new StreamingChatResponseHandler() {
					@Override
					public void onCompleteToolCall(dev.langchain4j.model.chat.response.CompleteToolCall call) {
						callCount.incrementAndGet();
					}

					@Override
					public void onCompleteResponse(ChatResponse value) {
						response.set(value);
						terminal.countDown();
					}

					@Override
					public void onError(Throwable throwable) {
						failure.set(throwable);
						terminal.countDown();
					}
				}
			);
		assertTrue(terminal.await(3, TimeUnit.SECONDS), () -> String.valueOf(failure.get()));
		assertNull(failure.get());
		assertEquals(2, callCount.get());
		assertEquals(
			List.of("call-one", "call-two"),
			response.get()
				.aiMessage()
				.toolExecutionRequests()
				.stream()
				.map(call -> call.id())
				.toList()
		);
	}

	@Test
	void partialContextsShareOneHandleAndLifecycleFinishesOnce() throws Exception {
		server.enqueue(
			sse(
				"data: {\"type\":\"response.output_text.delta\",\"delta\":\"text\"}\n\n"
					+ "data: {\"type\":\"response.reasoning_summary_text.delta\",\"delta\":\"thought\"}\n\n"
					+ "data: {\"type\":\"response.output_item.added\",\"item\":{\"id\":\"one\",\"type\":\"function_call\",\"call_id\":\"call-one\",\"name\":\"lookup\"}}\n\n"
					+ "data: {\"type\":\"response.function_call_arguments.delta\",\"item_id\":\"one\",\"call_id\":\"call-one\",\"delta\":\"{}\"}\n\n"
					+ "data: {\"type\":\"response.completed\",\"response\":{\"output\":[],\"usage\":{}}}\n\n"
			)
		);
		AtomicReference<dev.langchain4j.model.chat.response.StreamingHandle> started = new AtomicReference<>();
		AtomicReference<dev.langchain4j.model.chat.response.StreamingHandle> text = new AtomicReference<>();
		AtomicReference<dev.langchain4j.model.chat.response.StreamingHandle> thinking = new AtomicReference<>();
		AtomicReference<dev.langchain4j.model.chat.response.StreamingHandle> tool = new AtomicReference<>();
		AtomicInteger finishes = new AtomicInteger();
		CountDownLatch terminal = new CountDownLatch(1);
		CodexStreamingChatModel.builder()
			.credentials(new CodexCredentials("access", "refresh", "acct", Instant.now().plusSeconds(3600)))
			.endpoint(server.url("responses").toString())
			.streamingLifecycle(
				new CodexStreamingLifecycle() {
					@Override
					public void onStart(StreamingHandle handle) {
						started.set(handle);
					}

					@Override
					public void onFinish(StreamingHandle handle) {
						assertSame(started.get(), handle);
						finishes.incrementAndGet();
					}
				}
			)
			.build()
			.chat(
				request(),
				new StreamingChatResponseHandler() {
					@Override
					public void onPartialResponse(
							dev.langchain4j.model.chat.response.PartialResponse value,
							dev.langchain4j.model.chat.response.PartialResponseContext context) {
						text.set(context.streamingHandle());
					}

					@Override
					public void onPartialThinking(
							dev.langchain4j.model.chat.response.PartialThinking value,
							dev.langchain4j.model.chat.response.PartialThinkingContext context) {
						thinking.set(context.streamingHandle());
					}

					@Override
					public void onPartialToolCall(
							dev.langchain4j.model.chat.response.PartialToolCall value,
							dev.langchain4j.model.chat.response.PartialToolCallContext context) {
						tool.set(context.streamingHandle());
					}

					@Override
					public void onCompleteResponse(ChatResponse response) {
						terminal.countDown();
					}

					@Override
					public void onError(Throwable throwable) {
						terminal.countDown();
						fail(throwable);
					}
				}
			);
		assertTrue(terminal.await(3, TimeUnit.SECONDS));
		assertSame(started.get(), text.get());
		assertSame(started.get(), thinking.get());
		assertSame(started.get(), tool.get());
		assertEquals(1, finishes.get());
	}

	@Test
	void cancellationBeforeHeadersProducesOneErrorAndOneLifecycleFinish() throws Exception {
		server.enqueue(
			sse("data: {\"type\":\"response.completed\",\"response\":{\"output\":[],\"usage\":{}}}\n\n")
				.setHeadersDelay(2, TimeUnit.SECONDS)
		);
		AtomicReference<StreamingHandle> handle = new AtomicReference<>();
		AtomicReference<Throwable> failure = new AtomicReference<>();
		AtomicInteger finishes = new AtomicInteger();
		AtomicInteger terminals = new AtomicInteger();
		CountDownLatch done = new CountDownLatch(1);
		CodexStreamingChatModel.builder()
			.credentials(new CodexCredentials("access", "refresh", "acct", Instant.now().plusSeconds(3600)))
			.endpoint(server.url("responses").toString())
			.streamingLifecycle(
				new CodexStreamingLifecycle() {
					@Override
					public void onStart(StreamingHandle value) {
						handle.set(value);
					}

					@Override
					public void onFinish(StreamingHandle value) {
						finishes.incrementAndGet();
					}
				}
			)
			.build()
			.chat(
				request(),
				new StreamingChatResponseHandler() {
					@Override
					public void onCompleteResponse(ChatResponse response) {
						terminals.incrementAndGet();
						done.countDown();
					}

					@Override
					public void onError(Throwable throwable) {
						failure.set(throwable);
						terminals.incrementAndGet();
						done.countDown();
					}
				}
			);
		assertNotNull(handle.get());
		handle.get().cancel();
		assertTrue(done.await(3, TimeUnit.SECONDS));
		assertInstanceOf(java.util.concurrent.CancellationException.class, failure.get());
		assertEquals(1, terminals.get());
		assertEquals(1, finishes.get());
	}

	@Test
	void concurrentPersistenceNeverSharesTemporaryFile() throws Exception {
		java.nio.file.Path file = java.nio.file.Files.createTempDirectory("codex").resolve("auth.json");
		PathCredentialPersistence persistence = new PathCredentialPersistence(file);
		var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
		try {
			var first = pool.submit(
				() -> persistence.persist(new CodexCredentials("one", "one", "acct", Instant.now().plusSeconds(3600)))
			);
			var second = pool.submit(
				() -> persistence.persist(new CodexCredentials("two", "two", "acct", Instant.now().plusSeconds(3600)))
			);
			first.get(3, TimeUnit.SECONDS);
			second.get(3, TimeUnit.SECONDS);
			assertTrue(java.nio.file.Files.readString(file).contains("access_token"));
			try (var files = java.nio.file.Files.list(file.getParent())) {
				assertEquals(0, files.filter(path -> path.getFileName().toString().endsWith(".tmp"))
					.count());
			}
		}
		finally {
			pool.shutdownNow();
		}
	}

	private CodexStreamingChatModel model() {
		return model(new CodexCredentials("access", "refresh", "acct", Instant.now().plusSeconds(3600)), null);
	}

	private CodexStreamingChatModel model(CodexCredentials credentials, CredentialPersistence persistence) {
		return CodexStreamingChatModel.builder()
			.credentials(credentials)
			.credentialPersistence(persistence)
			.endpoint(server.url("responses").toString())
			.refreshEndpoint(server.url("oauth/token").toString())
			.build();
	}

	private static ChatRequest request() {
		return ChatRequest.builder().messages(List.of(UserMessage.from("x"))).build();
	}

	private static StreamingChatResponseHandler handler(CountDownLatch completed, AtomicReference<Throwable> failure) {
		return new StreamingChatResponseHandler() {
			@Override
			public void onCompleteResponse(ChatResponse response) {
				completed.countDown();
			}

			@Override
			public void onError(Throwable throwable) {
				failure.set(throwable);
				completed.countDown();
			}
		};
	}

	private static MockResponse sse(String body) {
		return new MockResponse().addHeader("Content-Type", "text/event-stream").setBody(body);
	}
}
