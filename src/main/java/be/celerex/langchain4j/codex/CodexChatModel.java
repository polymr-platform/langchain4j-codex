package be.celerex.langchain4j.codex;

import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.Set;

public final class CodexChatModel implements ChatModel {
	private final CodexSession session;
	private final ChatRequestParameters defaultRequestParameters;
	private final CodexReasoningEffort reasoningEffort;
	private final CodexReasoningSummary reasoningSummary;

	private CodexChatModel(Builder builder) {
		session = builder.session == null ? builder.sessionBuilder.build() : builder.session;
		defaultRequestParameters = ChatRequestParameters.builder().modelName(builder.modelName).build();
		reasoningEffort = builder.reasoningEffort;
		reasoningSummary = builder.reasoningSummary;
	}

	public static Builder builder() {
		return new Builder();
	}

	@Override
	public ChatResponse doChat(ChatRequest request) {
		try {
			var response = session.send(CodexRequestMapper.request(request, false, reasoningEffort, reasoningSummary));
			if (response.statusCode() / 100 != 2) {
				throw new IllegalStateException(CodexRequestMapper.errorMessage(response.statusCode(), response.body()));
			}
			return CodexRequestMapper.response(response.body());
		}
		catch (CodexAuthenticationException exception) {
			throw exception;
		}
		catch (Exception exception) {
			throw new IllegalStateException("Codex request failed", exception);
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

	@Override
	public Set<Capability> supportedCapabilities() {
		return Set.of();
	}

	public static final class Builder {
		private CodexSession session;
		private String modelName = "gpt-5-codex";
		private CodexReasoningEffort reasoningEffort = CodexReasoningEffort.MEDIUM;
		private CodexReasoningSummary reasoningSummary = CodexReasoningSummary.AUTO;
		private final CodexSession.Builder sessionBuilder = CodexSession.builder();

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

		public CodexChatModel build() {
			return new CodexChatModel(this);
		}
	}
}
