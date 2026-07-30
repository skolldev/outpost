package dev.outpost.ingest;

import jakarta.servlet.http.HttpServletRequest;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
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

	private final Path directory;
	private final int maxEnvelopeWireBytes;
	private final int maxEnvelopeDecompressedBytes;

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
			Files.createDirectories(directory);
			path = Files.createTempFile(directory, "envelope-", ".spool");
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
		try {
			Files.deleteIfExists(path);
		}
		catch (IOException e) {
			log.warn("failed to remove ingest spool file {}: {}", path, e.toString());
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
