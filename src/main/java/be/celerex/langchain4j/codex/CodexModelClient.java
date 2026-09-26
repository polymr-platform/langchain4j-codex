package be.celerex.langchain4j.codex;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Authenticated Codex model-catalog client. */
public final class CodexModelClient {
	public static final String DEFAULT_ENDPOINT = "https://chatgpt.com/backend-api/codex/models";
	public static final int DEFAULT_MAX_BODY_BYTES = 2 * 1024 * 1024;
	private final CodexSession session;
	private final URI endpoint;
	private final String clientVersion;
	private final int maxBodyBytes;
	private volatile CodexModelCatalog cached;

	private CodexModelClient(Builder builder) {
		session = builder.session == null ? builder.sessionBuilder.build() : builder.session;
		endpoint = URI.create(builder.endpoint == null ? DEFAULT_ENDPOINT : builder.endpoint);
		clientVersion = builder.clientVersion == null ? libraryVersion() : builder.clientVersion;
		maxBodyBytes = builder.maxBodyBytes;
	}

	public static Builder builder() {
		return new Builder();
	}

	public CodexModelCatalog listModels() {
		try {
			CodexModelCatalog previous = cached;
			HttpResponse<InputStream> response = session.get(requestUri(), HttpResponse.BodyHandlers.ofInputStream(), previous == null ? null : previous.etag());
			if (response.statusCode() == 304 && previous != null) {
				return new CodexModelCatalog(previous.models(), previous.etag(), true);
			}
			try (InputStream body = response.body()) {
				byte[] bytes = readBounded(body);
				if (response.statusCode() / 100 != 2) {
					throw new IllegalStateException(CodexRequestMapper.errorMessage(response.statusCode(), new String(bytes, StandardCharsets.UTF_8)));
				}
				JsonNode models = CodexCredentials.JSON.readTree(bytes).path("models");
				if (!models.isArray()) {
					throw new IllegalArgumentException("Invalid Codex model catalog: models must be an array");
				}
				List<CodexModel> parsed = new ArrayList<>();
				for (JsonNode model : models) {
					parsed.add(parse(model));
				}
				CodexModelCatalog result = new CodexModelCatalog(parsed, response.headers().firstValue("etag").orElse(null), false);
				cached = result;
				return result;
			}
		}
		catch (CodexAuthenticationException exception) {
			throw exception;
		}
		catch (Exception exception) {
			throw new IllegalStateException("Codex model discovery failed", exception);
		}
	}

	private URI requestUri() {
		String separator = endpoint.getQuery() == null ? "?" : "&";
		return URI.create(
			endpoint + separator + "client_version=" + URLEncoder.encode(clientVersion, StandardCharsets.UTF_8)
		);
	}

	private byte[] readBounded(InputStream input) throws IOException {
		ByteArrayOutputStream output = new ByteArrayOutputStream();
		byte[] buffer = new byte[8192];
		for (int read; (read = input.read(buffer)) != -1;) {
			if (output.size() + read > maxBodyBytes) {
				throw new IllegalArgumentException("Codex model catalog exceeds " + maxBodyBytes + " bytes");
			}
			output.write(buffer, 0, read);
		}
		return output.toByteArray();
	}

	private static CodexModel parse(JsonNode model) {
		String slug = required(model, "slug");
		List<CodexReasoningEffort> efforts = new ArrayList<>();
		JsonNode levels = model.path("supported_reasoning_levels");
		if (!levels.isArray()) {
			levels = model.path("supported_reasoning_efforts");
		}
		for (JsonNode level : levels) {
			String value = level.isTextual()
				? level.asText()
				: level.path("effort").asText(level.path("reasoning_effort").asText());
			try {
				efforts.add(CodexReasoningEffort.fromValue(value));
			}
			catch (IllegalArgumentException ignored) {
				// Future catalog values are intentionally not exposed as stable enum values.
			}
		}
		List<String> modalities = new ArrayList<>();
		for (JsonNode modality : model.path("input_modalities")) modalities.add(modality.asText());
		return new CodexModel(
			slug,
			model.path("display_name").asText(slug),
			nullable(model, "description"),
			nullableLong(model, "context_window"),
			model.path("supported_in_api").asBoolean(false),
			model.path("visibility").asText("none"),
			nullableEffort(model.path("default_reasoning_level").asText(null)),
			efforts,
			nullableSummary(model.path("default_reasoning_summary").asText(null)),
			modalities,
			modalities.contains("image"),
			model.has("priority")
					&& model.get("priority").canConvertToInt()
				? model.get("priority").intValue()
				: null
		);
	}

	private static String required(JsonNode node, String name) {
		String value = node.path(name).asText();
		if (value.isBlank()) {
			throw new IllegalArgumentException("Invalid Codex model catalog: model " + name + " is required");
		}
		return value;
	}

	private static String nullable(JsonNode node, String name) {
		return node.hasNonNull(name) ? node.get(name).asText() : null;
	}

	private static Long nullableLong(JsonNode node, String name) {
		return node.has(name) && node.get(name).canConvertToLong() ? node.get(name).longValue() : null;
	}

	private static CodexReasoningEffort nullableEffort(String value) {
		try {
			return value == null ? null : CodexReasoningEffort.fromValue(value);
		}
		catch (IllegalArgumentException ignored) {
			return null;
		}
	}

	private static CodexReasoningSummary nullableSummary(String value) {
		try {
			return value == null ? null : CodexReasoningSummary.fromValue(value);
		}
		catch (IllegalArgumentException ignored) {
			return null;
		}
	}

	private static String libraryVersion() {
		Package value = CodexModelClient.class.getPackage();
		return value.getImplementationVersion() == null ? "1.0.0" : value.getImplementationVersion();
	}

	public static final class Builder {
		private CodexSession session;
		private String endpoint;
		private String clientVersion;
		private int maxBodyBytes = DEFAULT_MAX_BODY_BYTES;
		private final CodexSession.Builder sessionBuilder = CodexSession.builder();

		public Builder session(CodexSession value) {
			session = value;
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

		public Builder httpClient(java.net.http.HttpClient value) {
			sessionBuilder.httpClient(value);
			return this;
		}

		public Builder credentialPersistence(CredentialPersistence value) {
			sessionBuilder.credentialPersistence(value);
			return this;
		}

		public Builder endpoint(String value) {
			endpoint = value;
			return this;
		}

		public Builder clientVersion(String value) {
			clientVersion = value;
			return this;
		}

		public Builder maxBodyBytes(int value) {
			if (value < 1) {
				throw new IllegalArgumentException("maxBodyBytes must be positive");
			}
			maxBodyBytes = value;
			return this;
		}

		public CodexModelClient build() {
			return new CodexModelClient(this);
		}
	}
}
