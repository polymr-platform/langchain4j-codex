package be.celerex.langchain4j.codex;

@FunctionalInterface
public interface CredentialPersistence {
	void persist(CodexCredentials credentials);
}
