package be.celerex.langchain4j.codex;

import java.util.List;

/** Immutable safe subset of Codex catalog metadata. */
public record CodexModel(
	String slug,
	String displayName,
	String description,
	Long contextWindow,
	boolean supportedInApi,
	String visibility,
	CodexReasoningEffort defaultReasoningEffort,
	List<CodexReasoningEffort> supportedReasoningEfforts,
	CodexReasoningSummary defaultReasoningSummary,
	List<String> inputModalities,
	boolean supportsImages,
	Integer priority
) {
	public CodexModel {
		supportedReasoningEfforts = List.copyOf(supportedReasoningEfforts);
		inputModalities = List.copyOf(inputModalities);
	}

	public boolean isApiVisible() {
		return supportedInApi && "list".equals(visibility);
	}
}
