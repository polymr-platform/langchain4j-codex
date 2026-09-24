package be.celerex.langchain4j.codex;

import dev.langchain4j.model.output.TokenUsage;

/** Standard LangChain4j token totals with Codex Responses usage details. */
public final class CodexTokenUsage extends TokenUsage {
	private final InputTokensDetails inputTokensDetails;
	private final OutputTokensDetails outputTokensDetails;

	CodexTokenUsage(
			Integer inputTokens,
			Integer outputTokens,
			Integer totalTokens,
			Integer cachedInputTokens,
			Integer cacheWriteInputTokens,
			Integer reasoningTokens) {
		super(inputTokens, outputTokens, totalTokens);
		inputTokensDetails = new InputTokensDetails(cachedInputTokens, cacheWriteInputTokens);
		outputTokensDetails = new OutputTokensDetails(reasoningTokens);
	}

	public InputTokensDetails inputTokensDetails() {
		return inputTokensDetails;
	}

	public OutputTokensDetails outputTokensDetails() {
		return outputTokensDetails;
	}

	public Integer reasoningTokenCount() {
		return outputTokensDetails.reasoningTokens();
	}

	public record InputTokensDetails(Integer cachedTokens, Integer cacheWriteTokens) {}

	public record OutputTokensDetails(Integer reasoningTokens) {}
}
