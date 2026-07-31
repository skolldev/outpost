package dev.outpost.ingest;

import jakarta.servlet.http.HttpServletRequest;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Writes request wire bodies to disk before they enter the ingest queue. Gzip
 * bodies stay compressed in the spool and are decoded through a bounded stream
 * when inspected or digested.
 */
@Component
public class EnvelopeSpool {

	private static final Logger log = LoggerFactory.getLogger(EnvelopeSpool.class);
	private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS =
			PosixFilePermissions.fromString("rwx------");
	private static final Set<PosixFilePermission> FILE_PERMISSIONS =
			PosixFilePermissions.fromString("rw-------");
	private static final String FILE_GLOB = "envelope-*.spool";

	/**
	 * Files this process has written and not yet deleted — queued, in flight, or
	 * mid-write. A crash or a digest that never completes leaves the file on disk
	 * with no entry here, which is what {@link #reap(Duration)} sweeps.
	 */
	private final Set<Path> live = ConcurrentHashMap.newKeySet();

	private final Path directory;
	private final int maxEnvelopeWireBytes;
	private final int maxEnvelopeDecompressedBytes;
	private volatile boolean directoryReady;

	public EnvelopeSpool(@Value("${outpost.ingest.spool-directory:${java.io.tmpdir}/outpost-ingest}") Path directory,
			@Value("${outpost.ingest.max-envelope-wire-bytes:4194304}") int maxEnvelopeWireBytes,
			@Value("${outpost.ingest.max-envelope-decompressed-bytes:20971520}") int maxEnvelopeDecompressedBytes) {
		if (maxEnvelopeWireBytes <= 0) {
			throw new IllegalArgumentException("outpost.ingest.max-envelope-wire-bytes must be positive");
		}
		if (maxEnvelopeDecompressedBytes <= 0) {
			throw new IllegalArgumentException("outpost.ingest.max-envelope-decompressed-bytes must be positive");
		}
		this.directory = directory;
		this.maxEnvelopeWireBytes = maxEnvelopeWireBytes;
		this.maxEnvelopeDecompressedBytes = maxEnvelopeDecompressedBytes;
	}

	public SpoolFile write(HttpServletRequest request) {
		boolean gzip = "gzip".equalsIgnoreCase(request.getHeader("Content-Encoding"));
		int limit = gzip ? maxEnvelopeWireBytes : Math.min(maxEnvelopeWireBytes, maxEnvelopeDecompressedBytes);
		String limitName = limit == maxEnvelopeWireBytes ? "wire" : "decompressed";
		if (request.getContentLengthLong() > limit) {
			throw oversize(limitName, limit);
		}

		Path path;
		try {
			ensureDirectory();
			path = createTempFile();
			live.add(path);
		}
		catch (IOException e) {
			throw new SpoolWriteException(e);
		}

		try (InputStream in = request.getInputStream(); OutputStream out = Files.newOutputStream(path)) {
			copyLimited(in, out, limit, limitName);
		}
		catch (EnvelopeParser.OversizeException e) {
			delete(path);
			throw e;
		}
		catch (IOException e) {
			delete(path);
			throw new SpoolWriteException(e);
		}

		return new SpoolFile(path, gzip);
	}

	private void ensureDirectory() throws IOException {
		if (directoryReady) {
			return;
		}
		synchronized (this) {
			if (directoryReady) {
				return;
			}
			Files.createDirectories(directory);
			try {
				Files.setPosixFilePermissions(directory, DIRECTORY_PERMISSIONS);
			}
			catch (UnsupportedOperationException e) {
				// Non-POSIX filesystems use their native temp-directory ACLs.
			}
			directoryReady = true;
		}
	}

	private Path createTempFile() throws IOException {
		try {
			return createTempFileWithPermissions();
		}
		catch (NoSuchFileException e) {
			synchronized (this) {
				directoryReady = false;
			}
			ensureDirectory();
			return createTempFileWithPermissions();
		}
	}

	private Path createTempFileWithPermissions() throws IOException {
		try {
			return Files.createTempFile(directory, "envelope-", ".spool",
					PosixFilePermissions.asFileAttribute(FILE_PERMISSIONS));
		}
		catch (UnsupportedOperationException e) {
			return Files.createTempFile(directory, "envelope-", ".spool");
		}
	}

	public InputStream open(SpoolFile file) throws IOException {
		InputStream wire = Files.newInputStream(file.path());
		if (!file.gzip()) {
			return wire;
		}
		try {
			return new LimitedInputStream(new GZIPInputStream(wire), maxEnvelopeDecompressedBytes);
		}
		catch (IOException e) {
			wire.close();
			throw e;
		}
	}

	public void delete(SpoolFile file) {
		delete(file.path());
	}

	private void delete(Path path) {
		live.remove(path);
		try {
			Files.deleteIfExists(path);
		}
		catch (IOException e) {
			log.warn("failed to remove ingest spool file {}: {}", path, e.toString());
		}
	}

	/**
	 * Removes spool files that no live queue entry points at and that have not
	 * been touched for {@code maxAge}. Both conditions must hold: the liveness
	 * check is what makes the sweep safe within this process, and the age gate
	 * covers the window it cannot — a file created but not yet registered.
	 */
	public Sweep reap(Duration maxAge) {
		Instant cutoff = Instant.now().minus(maxAge);
		int files = 0;
		long bytes = 0;
		try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory, FILE_GLOB)) {
			for (Path path : entries) {
				OptionalLong size = reapableSize(path, cutoff);
				if (size.isEmpty()) {
					continue;
				}
				try {
					if (Files.deleteIfExists(path)) {
						files++;
						bytes += size.getAsLong();
					}
				}
				catch (IOException e) {
					log.warn("failed to reap orphaned ingest spool file {}: {}", path, e.toString());
				}
			}
		}
		catch (NoSuchFileException e) {
			// Nothing has been spooled yet, so the directory does not exist.
		}
		catch (IOException e) {
			log.warn("failed to sweep ingest spool directory {}: {}", directory, e.toString());
		}
		return new Sweep(files, bytes);
	}

	/** The file's size when it should be reaped, empty when it should be kept. */
	private OptionalLong reapableSize(Path path, Instant cutoff) {
		if (live.contains(path)) {
			return OptionalLong.empty();
		}
		try {
			if (Files.getLastModifiedTime(path).toInstant().isAfter(cutoff)) {
				return OptionalLong.empty();
			}
			return OptionalLong.of(Files.size(path));
		}
		catch (NoSuchFileException e) {
			return OptionalLong.empty(); // A worker finished with it between the listing and now.
		}
		catch (IOException e) {
			log.warn("failed to inspect ingest spool file {}: {}", path, e.toString());
			return OptionalLong.empty();
		}
	}

	/** What one sweep removed. */
	public record Sweep(int files, long bytes) {

		public boolean isEmpty() {
			return files == 0;
		}

	}

	private void copyLimited(InputStream in, OutputStream out, int limit, String limitName) throws IOException {
		byte[] buffer = new byte[8192];
		int total = 0;
		while (true) {
			int read = in.read(buffer, 0, Math.min(buffer.length, limit - total + 1));
			if (read < 0) {
				return;
			}
			total += read;
			if (total > limit) {
				throw oversize(limitName, limit);
			}
			out.write(buffer, 0, read);
		}
	}

	private EnvelopeParser.OversizeException oversize(String limitName, int limit) {
		return new EnvelopeParser.OversizeException(
				"envelope exceeds " + limitName + " size limit of " + limit + " bytes");
	}

	public static class SpoolWriteException extends RuntimeException {
		SpoolWriteException(IOException cause) {
			super(cause);
		}
	}

	public static class SpoolReadException extends RuntimeException {
		SpoolReadException(IOException cause) {
			super(cause);
		}
	}

	private static class LimitedInputStream extends FilterInputStream {

		private final int limit;
		private int count;

		LimitedInputStream(InputStream in, int limit) {
			super(in);
			this.limit = limit;
		}

		@Override
		public int read() throws IOException {
			int value = super.read();
			if (value >= 0 && ++count > limit) {
				throw oversize();
			}
			return value;
		}

		@Override
		public int read(byte[] bytes, int offset, int length) throws IOException {
			int read = super.read(bytes, offset, Math.min(length, Math.max(1, limit - count + 1)));
			if (read > 0 && (count += read) > limit) {
				throw oversize();
			}
			return read;
		}

		private EnvelopeParser.OversizeException oversize() {
			return new EnvelopeParser.OversizeException(
					"envelope exceeds decompressed size limit of " + limit + " bytes");
		}
	}
}
