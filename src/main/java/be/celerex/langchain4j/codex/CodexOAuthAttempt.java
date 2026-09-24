package be.celerex.langchain4j.codex;

import java.net.URI;
import java.util.concurrent.atomic.AtomicBoolean;

/** A single authorization-code login attempt. Keep this object server-side until callback completion. */
public final class CodexOAuthAttempt {
	private final URI authorizationUri;
	private final String state;
	private final String codeVerifier;
	private final URI redirectUri;
	private final AtomicBoolean used = new AtomicBoolean();

	CodexOAuthAttempt(URI authorizationUri, String state, String codeVerifier, URI redirectUri) {
		this.authorizationUri = authorizationUri;
		this.state = state;
		this.codeVerifier = codeVerifier;
		this.redirectUri = redirectUri;
	}

	public URI authorizationUri() {
		return authorizationUri;
	}

	/** State may be stored with the user's server-side login session for callback correlation. */
	public String state() {
		return state;
	}

	/** Encrypted storage callers may persist this opaque, secret snapshot between redirect and callback. */
	public Snapshot snapshot() {
		return new Snapshot(state, codeVerifier, redirectUri.toString());
	}

	public static CodexOAuthAttempt restore(Snapshot snapshot) {
		if (snapshot == null
				|| snapshot.state() == null
				|| snapshot.codeVerifier() == null
				|| snapshot.redirectUri() == null) {
			throw new IllegalArgumentException("OAuth attempt snapshot is required");
		}
		return new CodexOAuthAttempt(null, snapshot.state(), snapshot.codeVerifier(), URI.create(snapshot.redirectUri()));
	}

	public record Snapshot(String state, String codeVerifier, String redirectUri) {
		@Override
		public String toString() {
			return "CodexOAuthAttempt.Snapshot[redacted]";
		}
	}

	boolean use() {
		return used.compareAndSet(false, true);
	}

	String expectedState() {
		return state;
	}

	String codeVerifier() {
		return codeVerifier;
	}

	URI redirectUri() {
		return redirectUri;
	}

	@Override
	public String toString() {
		return "CodexOAuthAttempt[authorizationUri=<redacted>, state=<redacted>]";
	}
}
