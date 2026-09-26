package be.celerex.langchain4j.codex;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** Thread-safe credential and HTTP state that may be shared by sync and streaming models. */
public final class CodexSession {
	public static final String DEFAULT_ENDPOINT = "https://chatgpt.com/backend-api/codex/responses";
	public static final String DEFAULT_REFRESH_ENDPOINT = "https://auth.openai.com/oauth/token";
	public static final String DEFAULT_CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann";
	private final HttpClient httpClient;
	private final URI endpoint;
	private final URI refreshEndpoint;
	private final String clientId;
	private final String originator;
	private final String userAgent;
	private final CredentialPersistence persistence;
	private final Executor executor;
	private volatile CodexCredentials credentials;

	private CodexSession(Builder builder) {
		httpClient = builder.httpClient == null ? HttpClient.newHttpClient() : builder.httpClient;
		endpoint = URI.create(builder.endpoint == null ? DEFAULT_ENDPOINT : builder.endpoint);
		refreshEndpoint = URI.create(builder.refreshEndpoint == null ? DEFAULT_REFRESH_ENDPOINT : builder.refreshEndpoint);
		clientId = builder.clientId == null ? DEFAULT_CLIENT_ID : builder.clientId;
		originator = builder.originator == null ? "codex_cli_rs" : builder.originator;
		userAgent = builder.userAgent == null ? "langchain4j-codex/1.0" : builder.userAgent;
		credentials = builder.credentials;
		persistence = builder.persistence;
		executor = builder.executor == null ? java.util.concurrent.ForkJoinPool.commonPool() : builder.executor;
	}

	public static Builder builder() {
		return new Builder();
	}

	HttpResponse<String> send(String payload) throws Exception {
		return send(payload, HttpResponse.BodyHandlers.ofString());
	}

	<T> HttpResponse<T> send(String payload, HttpResponse.BodyHandler<T> handler) throws Exception {
		CodexCredentials before = validCredentials();
		HttpResponse<T> response = sendOnce(payload, handler, before);
		if (response.statusCode() != 401) {
			return response;
		}
		refreshAfterUnauthorized(before);
		return sendOnce(payload, handler, credentials);
	}

	<T> CompletableFuture<HttpResponse<T>> sendAsync(String payload, HttpResponse.BodyHandler<T> handler) {
		return CompletableFuture.supplyAsync(
				() -> {
					try {
						return validCredentials();
					}
					catch (Exception exception) {
						throw new java.util.concurrent.CompletionException(exception);
					}
				},
				executor
			)
			.thenCompose(
				before -> sendOnceAsync(payload, handler, before)
					.thenCompose(
						response -> {
							if (response.statusCode() != 401) {
								return CompletableFuture.completedFuture(response);
							}
							return CompletableFuture.runAsync(
									() -> {
										try {
											refreshAfterUnauthorized(before);
										}
										catch (Exception exception) {
											throw new java.util.concurrent.CompletionException(exception);
										}
									},
									executor
								)
								.thenCompose(ignored -> sendOnceAsync(payload, handler, credentials));
						}
					)
			);
	}

	<T> HttpResponse<T> get(URI uri, HttpResponse.BodyHandler<T> handler, String etag) throws Exception {
		CodexCredentials before = validCredentials();
		HttpResponse<T> response = httpClient.send(getRequest(uri, before, etag), handler);
		if (response.statusCode() != 401) {
			return response;
		}
		refreshAfterUnauthorized(before);
		return httpClient.send(getRequest(uri, credentials, etag), handler);
	}

	private <T> CompletableFuture<HttpResponse<T>> sendOnceAsync(String payload, HttpResponse.BodyHandler<T> handler, CodexCredentials current) {
		return httpClient.sendAsync(request(payload, current), handler);
	}

	private <T> HttpResponse<T> sendOnce(String payload, HttpResponse.BodyHandler<T> handler, CodexCredentials current) throws Exception {
		return httpClient.send(request(payload, current), handler);
	}

	private HttpRequest getRequest(URI uri, CodexCredentials current, String etag) {
		HttpRequest.Builder request = HttpRequest.newBuilder(uri)
			.timeout(Duration.ofMinutes(1))
			.header("Authorization", "Bearer " + current.accessToken())
			.header("chatgpt-account-id", current.accountId())
			.header("originator", originator)
			.header("user-agent", userAgent)
			.header("x-client-request-id", UUID.randomUUID().toString())
			.header("Accept", "application/json")
			.GET();
		if (etag != null && !etag.isBlank()) {
			request.header("If-None-Match", etag);
		}
		return request.build();
	}

	private HttpRequest request(String payload, CodexCredentials current) {
		HttpRequest request = HttpRequest.newBuilder(endpoint)
			.timeout(Duration.ofMinutes(5))
			.header("Authorization", "Bearer " + current.accessToken())
			.header("chatgpt-account-id", current.accountId())
			.header("originator", originator)
			.header("user-agent", userAgent)
			.header("x-client-request-id", UUID.randomUUID().toString())
			.header("Content-Type", "application/json")
			.header("Accept", "text/event-stream, application/json")
			.POST(HttpRequest.BodyPublishers.ofString(payload))
			.build();
		return request;
	}

	private CodexCredentials validCredentials() throws Exception {
		CodexCredentials current = credentials;
		if (current.expiresAt().isAfter(Instant.now().plusSeconds(300))) {
			return current;
		}
		synchronized (this) {
			if (!credentials.expiresAt().isAfter(Instant.now().plusSeconds(300))) {
				refresh();
			}
			return credentials;
		}
	}

	private void refreshAfterUnauthorized(CodexCredentials rejected) throws Exception {
		synchronized (this) {
			if (credentials == rejected) {
				refresh();
			}
		}
	}

	private void refresh() throws Exception {
		String requestBody = CodexCredentials.JSON.writeValueAsString(
			java.util.Map.of("grant_type", "refresh_token", "client_id", clientId, "refresh_token", credentials.refreshToken())
		);
		HttpResponse<String> response = httpClient.send(
			HttpRequest.newBuilder(refreshEndpoint)
				.header("Content-Type", "application/json")
				.header("Accept", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(requestBody))
				.build(),
			HttpResponse.BodyHandlers.ofString()
		);
		if (response.statusCode() / 100 != 2) {
			throw new CodexAuthenticationException("Codex credential refresh failed with HTTP " + response.statusCode());
		}
		JsonNode body = CodexCredentials.JSON.readTree(response.body());
		String access = body.path("access_token").asText();
		if (access.isBlank()) {
			throw new CodexAuthenticationException("Codex refresh response omitted access token");
		}
		String refresh = body.path("refresh_token").asText(credentials.refreshToken());
		String idToken = body.path("id_token").asText(null);
		long seconds = body.path("expires_in").asLong(3600);
		credentials = credentials.rotated(access, refresh, idToken, Instant.now().plusSeconds(seconds));
		if (persistence != null) {
			persistence.persist(credentials);
		}
	}

	public static final class Builder {
		private HttpClient httpClient;
		private String endpoint;
		private String refreshEndpoint;
		private String clientId;
		private String originator;
		private String userAgent;
		private CodexCredentials credentials;
		private CredentialPersistence persistence;
		private Executor executor;

		public Builder executor(Executor value) {
			executor = value;
			return this;
		}

		public Builder httpClient(HttpClient value) {
			httpClient = value;
			return this;
		}

		public Builder endpoint(String value) {
			endpoint = value;
			return this;
		}

		public Builder refreshEndpoint(String value) {
			refreshEndpoint = value;
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

		public Builder userAgent(String value) {
			userAgent = value;
			return this;
		}

		public Builder credentials(CodexCredentials value) {
			credentials = value;
			return this;
		}

		public Builder authJson(String value) {
			credentials = CodexCredentials.fromJson(value);
			return this;
		}

		public Builder credentialPersistence(CredentialPersistence value) {
			persistence = value;
			return this;
		}

		public CodexSession build() {
			if (credentials == null) {
				throw new IllegalStateException("Explicit Codex credentials are required");
			}
			return new CodexSession(this);
		}
	}
}
