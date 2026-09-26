package be.celerex.langchain4j.codex;

/** Reasoning-summary values supported by the Codex Responses backend. */
public enum CodexReasoningSummary {
	AUTO("auto"),
	CONCISE("concise"),
	DETAILED("detailed"),
	NONE("none");

	private final String value;

	CodexReasoningSummary(String value) {
		this.value = value;
	}

	public String value() {
		return value;
	}

	public static CodexReasoningSummary fromValue(String value) {
		for (CodexReasoningSummary summary : values()) {
			if (summary.value.equals(value)) {
				return summary;
			}
		}
		throw new IllegalArgumentException("Unsupported Codex reasoning summary: " + value);
	}
}
