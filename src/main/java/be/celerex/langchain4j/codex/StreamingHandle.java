package be.celerex.langchain4j.codex;

import java.io.InputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/** Cancellation handle for a Codex stream. */
public final class StreamingHandle implements dev.langchain4j.model.chat.response.StreamingHandle {
	private final AtomicBoolean cancelled = new AtomicBoolean();
	private final AtomicBoolean terminal = new AtomicBoolean();
	private volatile CompletableFuture<?> responseFuture;
	private volatile InputStream inputStream;
	private volatile Future<?> readerTask;

	void responseFuture(CompletableFuture<?> value) {
		responseFuture = value;
		if (isCancelled()) {
			value.cancel(true);
		}
	}

	void inputStream(InputStream value) {
		inputStream = value;
		if (isCancelled()) {
			close(value);
		}
	}

	void readerTask(Future<?> value) {
		readerTask = value;
		if (isCancelled()) {
			value.cancel(true);
		}
	}

	@Override
	public void cancel() {
		if (!cancelled.compareAndSet(false, true)) {
			return;
		}
		CompletableFuture<?> response = responseFuture;
		if (response != null) {
			response.cancel(true);
		}
		InputStream stream = inputStream;
		if (stream != null) {
			close(stream);
		}
		Future<?> task = readerTask;
		if (task != null) {
			task.cancel(true);
		}
	}

	private static void close(InputStream stream) {
		try {
			stream.close();
		}
		catch (Exception exception) {
			System.getLogger(StreamingHandle.class.getName())
				.log(System.Logger.Level.DEBUG, "Unable to close cancelled Codex stream", exception);
		}
	}

	@Override
	public boolean isCancelled() {
		return cancelled.get();
	}

	boolean completeTerminal() {
		return terminal.compareAndSet(false, true);
	}
}
