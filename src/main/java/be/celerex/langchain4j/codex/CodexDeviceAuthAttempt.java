package be.celerex.langchain4j.codex;

import java.net.URI;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

/** A server-side device authorization attempt. Persist only its encrypted snapshot. */
public final class CodexDeviceAuthAttempt {
	private final URI verificationUri;
	private final String userCode;
	private final String deviceAuthId;
	private final long interval;
	private final Instant expiresAt;
	private final AtomicBoolean completed = new AtomicBoolean();

	CodexDeviceAuthAttempt(URI verificationUri, String userCode, String deviceAuthId, long interval, Instant expiresAt) {
		this.verificationUri = verificationUri;
		this.userCode = userCode;
		this.deviceAuthId = deviceAuthId;
		this.interval = interval;
		this.expiresAt = expiresAt;
	}

	public URI verificationUri() {
		return verificationUri;
	}

	public String userCode() {
		return userCode;
	}

	public long interval() {
		return interval;
	}

	public Instant expiresAt() {
		return expiresAt;
	}

	public Snapshot snapshot() {
		return new Snapshot(verificationUri.toString(), userCode, deviceAuthId, interval, expiresAt);
	}

	public static CodexDeviceAuthAttempt restore(Snapshot snapshot) {
		if (snapshot == null
				|| snapshot.verificationUri() == null
				|| snapshot.userCode() == null
				|| snapshot.deviceAuthId() == null
				|| snapshot.expiresAt() == null) {
			throw new IllegalArgumentException("Device authorization snapshot is required");
		}
		return new CodexDeviceAuthAttempt(
			URI.create(snapshot.verificationUri()),
			snapshot.userCode(),
			snapshot.deviceAuthId(),
			snapshot.interval(),
			snapshot.expiresAt()
		);
	}

	boolean complete() {
		return completed.compareAndSet(false, true);
	}

	String deviceAuthId() {
		return deviceAuthId;
	}

	public record Snapshot(String verificationUri, String userCode, String deviceAuthId, long interval, Instant expiresAt) {
		@Override
		public String toString() {
			return "CodexDeviceAuthAttempt.Snapshot[redacted]";
		}
	}

	@Override
	public String toString() {
		return "CodexDeviceAuthAttempt[verificationUri=<redacted>, userCode=<redacted>]";
	}
}
