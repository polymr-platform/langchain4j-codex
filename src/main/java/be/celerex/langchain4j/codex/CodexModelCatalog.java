package be.celerex.langchain4j.codex;

import java.util.List;

/** Immutable result of a Codex model-catalog lookup. */
public record CodexModelCatalog(List<CodexModel> models, String etag, boolean notModified) {
	public CodexModelCatalog {
		models = List.copyOf(models);
	}

	public List<CodexModel> apiVisibleModels() {
		return models.stream().filter(CodexModel::isApiVisible).toList();
	}
}
