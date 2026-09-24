package be.celerex.langchain4j.codex;

public final class CodexAuthenticationException extends RuntimeException {
	public CodexAuthenticationException(String message, Throwable cause) {
		super(message, cause);
	}

	public CodexAuthenticationException(String message) {
		super(message);
	}
}
