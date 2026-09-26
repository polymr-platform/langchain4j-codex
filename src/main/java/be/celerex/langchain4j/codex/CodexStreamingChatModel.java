package be.celerex.langchain4j.codex;

import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.CompleteToolCall;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.PartialThinkingContext;
import dev.langchain4j.model.chat.response.PartialToolCall;
import dev.langchain4j.model.chat.response.PartialToolCallContext;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;

public final class CodexStreamingChatModel implements StreamingChatModel {
	private final CodexSession session;
	private final ChatRequestParameters defaultRequestParameters;
	private final Executor executor;
	private final CodexStreamingLifecycle lifecycle;
	private final CodexReasoningEffort reasoningEffort;
	private final CodexReasoningSummary reasoningSummary;

	private CodexStreamingChatModel(Builder builder) {
		if (builder.session == null && builder.executor != null) {
			builder.sessionBuilder.executor(builder.executor);
		}
		session = builder.session == null ? builder.sessionBuilder.build() : builder.session;
		defaultRequestParameters = ChatRequestParameters.builder().modelName(builder.modelName).build();
		executor = builder.executor == null ? ForkJoinPool.commonPool() : builder.executor;
		lifecycle = builder.lifecycle;
		reasoningEffort = builder.reasoningEffort;
		reasoningSummary = builder.reasoningSummary;
	}

	public static Builder builder() {
		return new Builder();
	}

	@Override
	public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
		String payload;
		try {
			payload = CodexRequestMapper.request(request, true, reasoningEffort, reasoningSummary);
		}
		catch (Throwable throwable) {
			handler.onError(throwable);
			return;
		}
		StreamingHandle handle = new StreamingHandle();
		try {
			lifecycle.onStart(handle);
		}
		catch (Throwable throwable) {
			System.getLogger(CodexStreamingChatModel.class.getName())
				.log(System.Logger.Level.WARNING, "Codex streaming lifecycle start failed", throwable);
		}
		CompletableFuture<HttpResponse<InputStream>> response = session.sendAsync(payload, HttpResponse.BodyHandlers.ofInputStream());
		handle.responseFuture(response);
		response.whenCompleteAsync(
			(value, failure) -> {
				if (failure != null) {
					finish(handle, handler, unwrap(failure));
					return;
				}
				if (handle.isCancelled()) {
					finish(handle, handler, new CancellationException("Codex stream cancelled"));
					return;
				}
				if (value.statusCode() / 100 != 2) {
					try (InputStream body = value.body()) {
						finish(
							handle,
							handler,
							new IllegalStateException(CodexRequestMapper.errorMessage(value.statusCode(), new String(body.readAllBytes())))
						);
					}
					catch (Exception exception) {
						finish(handle, handler, exception);
					}
					return;
				}
				handle.inputStream(value.body());
				Future<?> task = submit(() -> readStream(value.body(), handler, handle));
				handle.readerTask(task);
			},
			executor
		);
	}

	private Future<?> submit(Runnable task) {
		if (executor instanceof ExecutorService service) {
			return service.submit(task);
		}
		CompletableFuture<Void> future = CompletableFuture.runAsync(task, executor);
		return future;
	}

	private void readStream(InputStream input, StreamingChatResponseHandler handler, StreamingHandle handle) {
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(input))) {
			StreamState state = new StreamState();
			StringBuilder data = new StringBuilder();
			for (String line; !handle.isCancelled() && (line = reader.readLine()) != null;) {
				if (line.startsWith("data:")) {
					if (!data.isEmpty()) {
						data.append('\n');
					}
					data.append(line.substring(5).stripLeading());
				}
				else if (line.isBlank() && !data.isEmpty()) {
					String event = data.toString();
					handleEvent(event, handler, state, handle);
					if (isTerminal(event)) {
						return;
					}
					data.setLength(0);
				}
			}
			if (handle.isCancelled()) {
				throw new CancellationException("Codex stream cancelled");
			}
			if (!data.isEmpty()) {
				handleEvent(data.toString(), handler, state, handle);
				if (isTerminal(data.toString())) {
					return;
				}
			}
			throw new IllegalStateException("Codex stream ended before a terminal response event");
		}
		catch (Throwable throwable) {
			finish(
				handle,
				handler,
				handle.isCancelled() ? new CancellationException("Codex stream cancelled") : throwable
			);
		}
	}

	private void finish(StreamingHandle handle, StreamingChatResponseHandler handler, Throwable throwable) {
		if (!handle.completeTerminal()) {
			return;
		}
		try {
			if (throwable != null) {
				handler.onError(throwable);
			}
		}
		finally {
			try {
				lifecycle.onFinish(handle);
			}
			catch (Throwable exception) {
				System.getLogger(CodexStreamingChatModel.class.getName())
					.log(System.Logger.Level.WARNING, "Codex streaming lifecycle finish failed", exception);
			}
		}
	}

	private static Throwable unwrap(Throwable throwable) {
		return throwable instanceof java.util.concurrent.CompletionException
				&& throwable.getCause() != null
			? throwable.getCause()
			: throwable;
	}

	private static boolean isTerminal(String event) {
		try {
			String type = CodexCredentials.JSON.readTree(event).path("type").asText();
			return "response.completed".equals(type)
				|| "response.failed".equals(type)
				|| "response.incomplete".equals(type);
		}
		catch (Exception exception) {
			return false;
		}
	}

	private void handleEvent(String data, StreamingChatResponseHandler handler, StreamState state, StreamingHandle handle) throws Exception {
		if ("[DONE]".equals(data)) {
			return;
		}
		JsonNode event = CodexCredentials.JSON.readTree(data);
		String type = event.path("type").asText();
		if ("response.output_text.delta".equals(type)) {
			if (!handle.isCancelled()) {
				handler.onPartialResponse(new PartialResponse(event.path("delta").asText()), new PartialResponseContext(handle));
			}
		}
		else if ("response.reasoning_summary_text.delta".equals(type)
				|| "response.reasoning_text.delta".equals(type)) {
			String delta = event.path("delta").asText();
			state.thinking.append(delta);
			if (!handle.isCancelled()) {
				handler.onPartialThinking(new PartialThinking(delta), new PartialThinkingContext(handle));
			}
		}
		else if ("response.output_item.added".equals(type)
				&& "function_call".equals(event.path("item").path("type").asText())) {
			JsonNode item = event.path("item");
			state.start(item);
		}
		else if ("response.function_call_arguments.delta".equals(type)) {
			state.addArguments(event, handler, handle);
		}
		else if ("response.output_item.done".equals(type)
				&& "function_call".equals(event.path("item").path("type").asText())) {
			state.complete(event.path("item"), handler);
		}
		else if ("response.completed".equals(type)) {
			ChatResponse response = CodexRequestMapper.response(event.path("response").toString());
			List<ToolExecutionRequest> calls = response.aiMessage().toolExecutionRequests();
			if ((calls == null || calls.isEmpty()) && !state.completed.isEmpty()) {
				calls = new ArrayList<>(state.completed);
			}
			String thinking = response.aiMessage().thinking();
			if ((thinking == null || thinking.isBlank()) && !state.thinking.isEmpty()) {
				thinking = state.thinking.toString();
			}
			response = ChatResponse.builder()
				.id(response.id())
				.modelName(response.modelName())
				.aiMessage(
					dev.langchain4j.data.message.AiMessage
						.builder()
						.text(response.aiMessage().text())
						.thinking(thinking)
						.toolExecutionRequests(calls)
						.build()
				)
				.tokenUsage(response.tokenUsage())
				.finishReason(
					calls != null
							&& !calls.isEmpty()
						? dev.langchain4j.model.output.FinishReason.TOOL_EXECUTION
						: response.finishReason()
				)
				.build();
			if (!handle.isCancelled() && handle.completeTerminal()) {
				try {
					handler.onCompleteResponse(response);
				}
				finally {
					lifecycle.onFinish(handle);
				}
			}
		}
		else if ("response.failed".equals(type) || "response.incomplete".equals(type) || "error".equals(type)) {
			JsonNode error = event.path("error").isMissingNode() ? event.path("response").path("error") : event.path("error");
			String detail = error.path("message").asText();
			if (detail.isBlank() && "response.incomplete".equals(type)) {
				detail = "Incomplete response, reason: "
					+ event.path("response")
						.path("incomplete_details")
						.path("reason")
						.asText("unknown");
			}
			throw new IllegalStateException("Codex stream " + type + (detail.isBlank() ? "" : ": " + detail));
		}
	}

	private static final class StreamState {
		private final Map<String, ToolCallState> calls = new LinkedHashMap<>();
		private final List<ToolExecutionRequest> completed = new ArrayList<>();
		private final StringBuilder thinking = new StringBuilder();

		void start(JsonNode item) {
			String key = key(item);
			ToolCallState call = calls.computeIfAbsent(
				key,
				ignored -> new ToolCallState(calls.size(), item.path("call_id").asText(key), item.path("name").asText(null))
			);
			call.update(item);
		}

		void addArguments(JsonNode event, StreamingChatResponseHandler handler, StreamingHandle handle) {
			String key = event.path("item_id").asText(event.path("call_id").asText());
			ToolCallState call = calls.computeIfAbsent(key, ignored -> new ToolCallState(calls.size(), event.path("call_id").asText(key), null));
			String delta = event.path("delta").asText();
			call.arguments.append(delta);
			if (!handle.isCancelled() && call.name != null && !call.name.isBlank()) {
				handler.onPartialToolCall(
					PartialToolCall.builder()
						.index(call.index)
						.id(call.id)
						.name(call.name)
						.partialArguments(delta)
						.build(),
					new PartialToolCallContext(handle)
				);
			}
		}

		void complete(JsonNode item, StreamingChatResponseHandler handler) {
			String key = key(item);
			ToolCallState call = calls.computeIfAbsent(
				key,
				ignored -> new ToolCallState(calls.size(), item.path("call_id").asText(key), item.path("name").asText())
			);
			call.update(item);
			String arguments = item.path("arguments").asText(call.arguments.toString());
			ToolExecutionRequest request = ToolExecutionRequest.builder()
				.id(item.path("call_id").asText(call.id))
				.name(item.path("name").asText(call.name))
				.arguments(arguments)
				.build();
			if (completed.stream()
				.anyMatch(existing -> existing.id().equals(request.id()))) {
				return;
			}
			completed.add(request);
			handler.onCompleteToolCall(new CompleteToolCall(call.index, request));
		}

		private static String key(JsonNode item) {
			return item.path("id").asText(item.path("call_id").asText());
		}
	}

	private static final class ToolCallState {
		private final int index;
		private String id;
		private String name;
		private final StringBuilder arguments = new StringBuilder();

		private ToolCallState(int index, String id, String name) {
			this.index = index;
			this.id = id;
			this.name = name;
		}

		private void update(JsonNode item) {
			if (item.hasNonNull("call_id")) {
				id = item.path("call_id").asText(id);
			}
			if (item.hasNonNull("name")) {
				name = item.path("name").asText(name);
			}
		}
	}

	@Override
	public ChatRequestParameters defaultRequestParameters() {
		return defaultRequestParameters;
	}

	@Override
	public ModelProvider provider() {
		return ModelProvider.OTHER;
	}

	public static final class Builder {
		private CodexSession session;
		private String modelName = "gpt-5-codex";
		private CodexReasoningEffort reasoningEffort = CodexReasoningEffort.MEDIUM;
		private CodexReasoningSummary reasoningSummary = CodexReasoningSummary.AUTO;
		private final CodexSession.Builder sessionBuilder = CodexSession.builder();
		private Executor executor;
		private CodexStreamingLifecycle lifecycle = CodexStreamingLifecycle.NO_OP;

		public Builder modelName(String value) {
			if (value == null || value.isBlank()) {
				throw new IllegalArgumentException("Codex model name is required");
			}
			modelName = value;
			return this;
		}

		public Builder reasoningEffort(CodexReasoningEffort value) {
			reasoningEffort = java.util.Objects.requireNonNull(value, "reasoningEffort");
			return this;
		}

		public Builder reasoningSummary(CodexReasoningSummary value) {
			reasoningSummary = java.util.Objects.requireNonNull(value, "reasoningSummary");
			return this;
		}

		public Builder session(CodexSession value) {
			session = value;
			return this;
		}

		public Builder executor(Executor value) {
			executor = value;
			return this;
		}

		public Builder streamingLifecycle(CodexStreamingLifecycle value) {
			lifecycle = value == null ? CodexStreamingLifecycle.NO_OP : value;
			return this;
		}

		public Builder httpClient(java.net.http.HttpClient value) {
			sessionBuilder.httpClient(value);
			return this;
		}

		public Builder endpoint(String value) {
			sessionBuilder.endpoint(value);
			return this;
		}

		public Builder refreshEndpoint(String value) {
			sessionBuilder.refreshEndpoint(value);
			return this;
		}

		public Builder clientId(String value) {
			sessionBuilder.clientId(value);
			return this;
		}

		public Builder originator(String value) {
			sessionBuilder.originator(value);
			return this;
		}

		public Builder userAgent(String value) {
			sessionBuilder.userAgent(value);
			return this;
		}

		public Builder credentials(CodexCredentials value) {
			sessionBuilder.credentials(value);
			return this;
		}

		public Builder authJson(String value) {
			sessionBuilder.authJson(value);
			return this;
		}

		public Builder credentialPersistence(CredentialPersistence value) {
			sessionBuilder.credentialPersistence(value);
			return this;
		}

		public CodexStreamingChatModel build() {
			return new CodexStreamingChatModel(this);
		}
	}
}
