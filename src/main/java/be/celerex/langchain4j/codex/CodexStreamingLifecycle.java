package be.celerex.langchain4j.codex;

/** Observes the lifetime of one streaming request. */
public interface CodexStreamingLifecycle {
	CodexStreamingLifecycle NO_OP = new CodexStreamingLifecycle() {
		@Override
		public void onStart(StreamingHandle handle) {}

		@Override
		public void onFinish(StreamingHandle handle) {}
	};

	void onStart(StreamingHandle handle);

	void onFinish(StreamingHandle handle);
}
