package be.celerex.langchain4j.codex;

/** Stable reasoning effort values accepted by the Codex model catalog. */
public enum CodexReasoningEffort {
	NONE("none"),
	MINIMAL("minimal"),
	LOW("low"),
	MEDIUM("medium"),
	HIGH("high"),
	XHIGH("xhigh"),
	MAX("max"),
	ULTRA("ultra"),
	PERSISTENT("persistent");

	private final String value;

	CodexReasoningEffort(String value) {
		this.value = value;
	}

	public String value() {
		return value;
	}

	public static CodexReasoningEffort fromValue(String value) {
		for (CodexReasoningEffort effort : values()) {
			if (effort.value.equals(value)) {
				return effort;
			}
		}
		throw new IllegalArgumentException("Unsupported stable Codex reasoning effort: " + value);
	}
}
