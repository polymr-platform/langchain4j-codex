package be.celerex.langchain4j.codex;

/** Result of one non-blocking device authorization poll. */
public sealed interface CodexDeviceAuthPollResult permits CodexDeviceAuthPollResult.Pending, CodexDeviceAuthPollResult.Complete {
	record Pending() implements CodexDeviceAuthPollResult { }
	record Complete(CodexCredentials credentials) implements CodexDeviceAuthPollResult { }
}
