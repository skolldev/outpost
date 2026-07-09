package dev.outpost.symbolication;

import tools.jackson.databind.ObjectMapper;
import dev.outpost.artifacts.BundleAssembledEvent;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.zip.GZIPInputStream;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Parsed-source-map lookup for the symbolicator (§6.2): by debug ID first,
 * release + minified-file name as the legacy fallback. Parsed maps (and
 * misses) are cached — a burst of errors from one release hits the same few
 * maps — and the cache is dropped whenever a new bundle is assembled.
 */
@Repository
public class ArtifactRepository {

	private static final int CACHE_SIZE = 16;

	private final JdbcClient jdbc;
	private final ObjectMapper mapper;

	private final Map<String, Optional<SourceMapConsumer>> byDebugId = lruCache();
	private final Map<String, Optional<SourceMapConsumer>> byReleasePath = lruCache();

	public ArtifactRepository(JdbcClient jdbc, ObjectMapper mapper) {
		this.jdbc = jdbc;
		this.mapper = mapper;
	}

	public Optional<SourceMapConsumer> findByDebugId(String debugId) {
		synchronized (byDebugId) {
			return byDebugId.computeIfAbsent(debugId, id -> jdbc
				.sql("SELECT content FROM artifact WHERE debug_id = ? AND artifact_type = 'source_map'")
				.param(id)
				.query(byte[].class)
				.optional()
				.map(this::parse));
		}
	}

	/** Legacy fallback: a source_map artifact named {@code <minified file>.map} in a bundle of this project+release. */
	public Optional<SourceMapConsumer> findByReleaseAndFileName(long projectId, String release, String fileName) {
		String key = projectId + "\n" + release + "\n" + fileName;
		synchronized (byReleasePath) {
			return byReleasePath.computeIfAbsent(key, k -> jdbc.sql("""
					SELECT a.content FROM artifact a
					JOIN artifact_bundle_release abr ON abr.bundle_id = a.bundle_id
					WHERE abr.project_id = ? AND abr.release = ? AND a.artifact_type = 'source_map'
					  AND (a.file_path = ? OR a.file_path LIKE '%/' || ?)
					ORDER BY a.id DESC LIMIT 1
					""")
				.param(projectId)
				.param(release)
				.param("~/" + fileName + ".map")
				.param(fileName + ".map")
				.query(byte[].class)
				.optional()
				.map(this::parse));
		}
	}

	// After commit (like the re-symbolication job), so a concurrent ingest
	// worker can't re-cache a miss from the not-yet-visible bundle.
	@TransactionalEventListener(fallbackExecution = true)
	public void onBundleAssembled(BundleAssembledEvent event) {
		synchronized (byDebugId) {
			byDebugId.clear();
		}
		synchronized (byReleasePath) {
			byReleasePath.clear();
		}
	}

	private SourceMapConsumer parse(byte[] gzippedContent) {
		try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gzippedContent))) {
			return SourceMapConsumer.parse(mapper.readTree(in));
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static <V> Map<String, V> lruCache() {
		return new LinkedHashMap<>(32, 0.75f, true) {
			@Override
			protected boolean removeEldestEntry(Map.Entry<String, V> eldest) {
				return size() > CACHE_SIZE;
			}
		};
	}
}
