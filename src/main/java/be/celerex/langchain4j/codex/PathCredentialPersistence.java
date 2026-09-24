package be.celerex.langchain4j.codex;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

/** Atomically persists auth.json with owner-only permissions on POSIX file systems. */
public final class PathCredentialPersistence implements CredentialPersistence {
	private static final System.Logger LOG = System.getLogger(PathCredentialPersistence.class.getName());
	private static final Set<PosixFilePermission> OWNER_ONLY = Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
	private final Path path;

	public PathCredentialPersistence(Path path) {
		this.path = path;
	}

	@Override
	public void persist(CodexCredentials credentials) {
		try {
			Path directory = path.toAbsolutePath().getParent();
			Files.createDirectories(directory);
			Path temporary = Files.createTempFile(directory, path.getFileName() + ".", ".tmp");
			try {
				Files.writeString(temporary, credentials.authJson());
				setOwnerOnly(temporary);
				Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
				setOwnerOnly(path);
			}
			finally {
				Files.deleteIfExists(temporary);
			}
		}
		catch (IOException exception) {
			throw new CodexAuthenticationException("Could not persist rotated Codex credentials", exception);
		}
	}

	private static void setOwnerOnly(Path file) throws IOException {
		try {
			Files.setPosixFilePermissions(file, OWNER_ONLY);
		}
		catch (UnsupportedOperationException exception) {
			LOG.log(
				System.Logger.Level.WARNING,
				"Cannot set owner-only credential file permissions on this file system",
				exception
			);
		}
	}
}
