# LangChain4j Codex

A standalone Java 21 Maven adapter for the ChatGPT Codex Responses backend and LangChain4j 1.14. It is not an official OpenAI SDK; the backend is undocumented and can change.

## Supported authentication methods

The library implements the authentication methods currently used by the open-source Codex client:

- device-code login, recommended for web, remote, and headless applications
- browser authorization-code login with PKCE and a localhost callback
- import of an existing Codex `auth.json`

Authentication produces `CodexCredentials`. Model execution does not depend on the Codex CLI, but the ChatGPT Codex backend is not a documented stable third-party API.

## Build and dependency

The project currently uses Java 21 and LangChain4j 1.14. Install it locally or publish it to your Maven repository:

```bash
mvn clean install
```

```xml
<dependency>
	<groupId>be.celerex</groupId>
	<artifactId>langchain4j-codex</artifactId>
	<version>1.0.0-SNAPSHOT</version>
</dependency>
```

## Create models from credentials

A `CodexSession` is thread-safe. Share one session between synchronous and streaming models so access-token refresh and rotating refresh-token persistence are coordinated:

```java
CredentialPersistence persistence = new PathCredentialPersistence(authJsonPath);
CodexCredentials credentials = CodexCredentials.fromJson(Files.readString(authJsonPath));
CodexSession session = CodexSession.builder()
	.credentials(credentials)
	.credentialPersistence(persistence)
	.httpClient(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build())
	.executor(applicationExecutor)
	.build();
CodexChatModel model = CodexChatModel.builder()
	.modelName("gpt-5.3-codex")
	.session(session)
	.build();
CodexStreamingChatModel streaming = CodexStreamingChatModel.builder()
	.modelName("gpt-5.3-codex")
	.session(session)
	.executor(applicationExecutor)
	.build();
```

Each model can select its own reasoning settings even when sharing a session:

```java
CodexChatModel preciseModel = CodexChatModel.builder()
	.session(session)
	.modelName("gpt-5.3-codex")
	.reasoningEffort(CodexReasoningEffort.HIGH)
	.reasoningSummary(CodexReasoningSummary.CONCISE)
	.build();
```

The defaults are `MEDIUM` and `AUTO`. Current named effort values are `NONE`, `MINIMAL`, `LOW`, `MEDIUM`, `HIGH`, `XHIGH`, `MAX`, `ULTRA`, and `PERSISTENT`; summaries are `AUTO`, `CONCISE`, `DETAILED`, and `NONE`. Use model discovery to determine which efforts a selected model supports.

Discover the authenticated account's current catalog with the same session. The client returns raw catalog metadata and `apiVisibleModels()` filters to list-visible API models:

```java
CodexModelCatalog catalog = CodexModelClient.builder().session(session).build().listModels();
for (CodexModel model : catalog.apiVisibleModels()) {
	System.out.println(model.slug());
}
```

Catalog requests use the Codex account headers, replay once after a 401 refresh, bound bodies to 2 MiB, and reuse an ETag when the same client instance is used.

`CodexSession.Builder.httpClient(...)` accepts an application-managed Java `HttpClient`. `CodexSession.Builder.executor(...)` controls asynchronous credential and retry work. `CodexStreamingChatModel.Builder.executor(...)` controls SSE parsing and callbacks. Executors and HTTP clients remain owned by the application.

### Import existing auth.json

A one-time import from a trusted Codex CLI installation is supported:

```java
String authJson = Files.readString(Path.of(System.getProperty("user.home"), ".codex", "auth.json"));
CodexCredentials credentials = CodexCredentials.fromJson(authJson);
```

Copy the value into application-managed encrypted storage rather than continuing to share the CLI file. Persist every refreshed credential document because OpenAI rotates refresh tokens.

### Browser OAuth with localhost callback

`CodexOAuthClient` is framework-neutral: it does not start an HTTP listener or launch a browser. The application must temporarily listen for `GET /auth/callback`, open the authorization URI, and pass the complete callback URI to `complete`.

The public Codex client ID currently accepts only these loopback redirects:

```text
http://localhost:1455/auth/callback
http://localhost:1457/auth/callback
```

Try port 1455 first and fall back to 1457, matching the Codex CLI. Arbitrary ports and hosted HTTPS callback URLs are rejected by OpenAI for this client ID. If both ports are unavailable, use device-code login.

```java
URI callback = URI.create("http://localhost:1455/auth/callback");
CodexOAuthClient oauth = CodexOAuthClient.builder()
	.credentialPersistence(new PathCredentialPersistence(authJsonPath))
	.build();
CodexOAuthAttempt attempt = oauth.start(callback);
CodexOAuthAttempt.Snapshot snapshot = attempt.snapshot();
// Encrypt snapshot in short-lived server-side storage, then open attempt.authorizationUri().
// In the localhost callback listener:
CodexOAuthAttempt restored = CodexOAuthAttempt.restore(decryptedSnapshot);
CodexCredentials credentials = oauth.complete(restored, fullCallbackUri);
```

Bind encrypted snapshots to the initiating user/session, expire and delete them promptly, and accept each callback once. Never put snapshots, state, authorization codes, PKCE verifiers, tokens, or `auth.json` in browser storage or logs. `complete` validates state before processing errors or codes, and an in-memory attempt is single-use. Durable replay protection remains the application’s responsibility after restoring a snapshot.

The adapter maps system messages to `instructions`, user and assistant text to Responses `message` items with content arrays, calls to `function_call`, and results to `function_call_output`. It sends only function tools. Unsupported LangChain4j request parameters are sampling (`temperature`, `topP`, `topK`, frequency/presence penalties), token limits, stop sequences, and non-text response formats; `toolChoice` is supported.

Credentials preserve an imported auth document, including `auth_mode`, `OPENAI_API_KEY`, token identity/account fields, and `last_refresh`. OAuth login produces Codex-compatible `auth.json` with `auth_mode: chatgpt`, nested raw `id_token`, and root `last_refresh`. Current ChatGPT refresh requests use JSON, begin five minutes before expiry, persist rotated refresh tokens atomically with POSIX `0600` permissions where available, and replay a stale 401 once after a concurrency-safe refresh. Credential `toString()` redacts secrets.

Streaming reads SSE incrementally through Java `HttpClient`. Text, reasoning, and tool-call deltas use LangChain4j context callbacks containing one shared `StreamingHandle`. Calling `cancel()` aborts the response future, closes the body stream, stops parsing, and produces one `CancellationException`. Frameworks can use `CodexStreamingLifecycle` to register and clear the handle in their own request-abort registry:

```java
CodexStreamingChatModel streaming = CodexStreamingChatModel.builder()
	.session(session)
	.streamingLifecycle(
		new CodexStreamingLifecycle() {
			@Override
			public void onStart(StreamingHandle handle) {
				requestRegistry.register(requestId, handle::cancel);
			}

			@Override
			public void onFinish(StreamingHandle handle) {
				requestRegistry.clear(requestId);
			}
		}
	)
	.build();
```

### Device-code login

Device-code login is the recommended flow for web applications, remote servers, containers, and headless deployments. It requires no callback listener or fixed port. The user may need to enable device-code authorization under ChatGPT security settings; managed workspaces may require administrator approval.

`start()` returns a verification URI, one-time user code, polling interval, and expiry. Persist only the encrypted snapshot. Poll no faster than `attempt.interval()` until the result is complete.

```java
CodexDeviceAuthClient device = CodexDeviceAuthClient.builder()
	.httpClient(applicationHttpClient)
	.timeout(Duration.ofSeconds(30))
	.credentialPersistence(new PathCredentialPersistence(authJsonPath))
	.build();
CodexDeviceAuthAttempt started = device.start();
CodexDeviceAuthAttempt.Snapshot encryptedValue = started.snapshot();
// Show started.verificationUri() and started.userCode() to the authenticated user.
CodexDeviceAuthAttempt attempt = CodexDeviceAuthAttempt.restore(decryptedSnapshot);
CodexDeviceAuthPollResult result = device.poll(attempt);
if (result instanceof CodexDeviceAuthPollResult.Pending) {
	// Persist the snapshot again and poll after attempt.interval() seconds.
}
else if (result instanceof CodexDeviceAuthPollResult.Complete complete) {
	CodexCredentials credentials = complete.credentials();
	// Delete the stored attempt and construct/share a CodexSession.
}
```

`complete(attempt)` is available for command-line applications that intentionally block and poll internally. Web request handlers should use `poll()` instead.

## Messages, tools, and usage

Supported mappings include:

- system messages to Responses `instructions`
- ordered user text and images
- image URLs and base64 images with MIME types
- assistant text history
- function tools, parallel tool calls, and tool results
- streamed text, reasoning, and tool-call deltas
- input, output, total, cached-input, cache-write, and reasoning token counts

Audio, PDF, video, assistant-image history, and `ULTRA_HIGH` image detail are rejected explicitly. The backend does not return a reliable per-response monetary subscription cost.

Unsupported request controls currently include sampling parameters, max output tokens, stop sequences, and non-text response formats. These fail explicitly rather than being silently ignored.

## Credential storage and refresh

Credentials preserve imported `auth.json` fields and OAuth produces Codex-compatible JSON with `auth_mode: chatgpt`. Refresh begins five minutes before access-token expiry, replaces rotating refresh tokens, persists the updated document, and retries one unauthorized request. Use one shared `CodexSession` per credential set. Multiple JVMs must coordinate credential refresh externally.

`PathCredentialPersistence` writes atomically and applies owner-only POSIX permissions where supported. Applications using databases or secret managers should implement `CredentialPersistence` and encrypt values at rest.

## Security and compatibility

This project reimplements behavior from OpenAI’s Apache-2.0 Codex source because there is no equivalent Java SDK. OpenAI may change OAuth client constraints, endpoints, headers, model metadata, or request formats. Keep protocol-specific changes inside this library and pin/test upgrades.

Never log or expose `CodexCredentials`, attempt snapshots, authorization codes, device IDs, PKCE verifiers, access tokens, refresh tokens, or raw `auth.json`.
