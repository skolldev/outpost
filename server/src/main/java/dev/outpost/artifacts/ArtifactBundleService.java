package dev.outpost.artifacts;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Chunk staging + synchronous artifact-bundle assembly (§4.4): concatenate
 * staged chunks, verify the SHA-1, unpack the zip, and store one artifact row
 * per manifest entry keyed by (debug_id, type). The raw bundle is kept for
 * re-processing; bundles are deduped by checksum. Publishes
 * {@link BundleAssembledEvent} so re-symbolication can pick up flagged events.
 */
@Service
public class ArtifactBundleService {

	/** A file entry can be a whole app's vendored bundle map; be generous but bounded. */
	private static final long MAX_FILE_SIZE = 256L * 1024 * 1024;

	private static final Logger log = LoggerFactory.getLogger(ArtifactBundleService.class);

	public sealed interface AssembleResult {
		record MissingChunks(List<String> missing) implements AssembleResult {
		}
		record Error(String detail) implements AssembleResult {
		}
		record Ok() implements AssembleResult {
		}
	}

	private final JdbcClient jdbc;
	private final ObjectMapper mapper;
	private final ApplicationEventPublisher events;

	public ArtifactBundleService(JdbcClient jdbc, ObjectMapper mapper, ApplicationEventPublisher events) {
		this.jdbc = jdbc;
		this.mapper = mapper;
		this.events = events;
	}

	public void stageChunk(String sha1, byte[] content) {
		jdbc.sql("INSERT INTO upload_chunk (sha1, content) VALUES (?, ?) ON CONFLICT (sha1) DO NOTHING")
			.param(sha1)
			.param(content)
			.update();
	}

	@Transactional
	public AssembleResult assemble(String checksum, List<String> chunks, List<String> projectSlugs, String release) {
		checksum = checksum.toLowerCase(Locale.ROOT);
		// Known bundle → done, before even looking at chunks: staged chunks are
		// deleted after assembly, and the CLI probes with assemble to skip
		// re-uploads of bundles the server already has.
		Optional<Long> existing = jdbc.sql("SELECT id FROM artifact_bundle WHERE checksum = ?")
			.param(checksum)
			.query(Long.class)
			.optional();
		if (existing.isPresent()) {
			associateReleases(existing.get(), projectSlugs, release);
			return new AssembleResult.Ok();
		}

		Map<String, byte[]> staged = new LinkedHashMap<>();
		List<String> missing = new ArrayList<>();
		for (String sha1 : chunks) {
			Optional<byte[]> content = jdbc.sql("SELECT content FROM upload_chunk WHERE sha1 = ?")
				.param(sha1.toLowerCase(Locale.ROOT))
				.query(byte[].class)
				.optional();
			content.ifPresentOrElse(bytes -> staged.put(sha1, bytes), () -> missing.add(sha1));
		}
		if (!missing.isEmpty()) {
			return new AssembleResult.MissingChunks(missing);
		}

		ByteArrayOutputStream assembled = new ByteArrayOutputStream();
		staged.values().forEach(bytes -> assembled.writeBytes(bytes));
		byte[] bundle = assembled.toByteArray();
		if (!ChunkUploadController.sha1(bundle).equals(checksum)) {
			return new AssembleResult.Error("bundle checksum mismatch");
		}

		Map<String, byte[]> files;
		try {
			files = unzip(bundle);
		}
		catch (IOException e) {
			return new AssembleResult.Error("bundle is not a valid zip: " + e.getMessage());
		}
		byte[] manifestBytes = files.get("manifest.json");
		if (manifestBytes == null) {
			return new AssembleResult.Error("bundle has no manifest.json");
		}
		JsonNode manifest = mapper.readTree(manifestBytes);

		long bundleId = jdbc.sql("INSERT INTO artifact_bundle (checksum, raw) VALUES (?, ?) RETURNING id")
			.param(checksum)
			.param(bundle)
			.query(Long.class)
			.single();
		int inserted = insertArtifacts(bundleId, manifest, files);
		if (release == null && manifest.hasNonNull("release")) {
			release = manifest.get("release").asText();
		}
		associateReleases(bundleId, projectSlugs, release);
		jdbc.sql("DELETE FROM upload_chunk WHERE sha1 = ANY(string_to_array(?, ','))")
			.param(String.join(",", staged.keySet()))
			.update();
		log.info("assembled artifact bundle {} ({} artifacts, release {})", checksum, inserted, release);
		events.publishEvent(new BundleAssembledEvent(bundleId, projectSlugs, release));
		return new AssembleResult.Ok();
	}

	/** Inserts source_map / minified_source entries that carry a debug ID; other types are skipped. */
	private int insertArtifacts(long bundleId, JsonNode manifest, Map<String, byte[]> files) {
		int inserted = 0;
		JsonNode manifestFiles = manifest.path("files");
		for (Map.Entry<String, JsonNode> property : manifestFiles.properties()) {
			JsonNode info = property.getValue();
			String type = info.path("type").asText("");
			if (!type.equals("source_map") && !type.equals("minified_source")) {
				continue;
			}
			String debugId = header(info.path("headers"), "debug-id");
			byte[] content = files.get(property.getKey());
			if (debugId == null || content == null) {
				continue;
			}
			String filePath = info.hasNonNull("url") ? info.get("url").asText() : property.getKey();
			inserted += jdbc.sql("""
					INSERT INTO artifact (bundle_id, debug_id, artifact_type, file_path, headers, content)
					VALUES (?, ?, ?, ?, ?::jsonb, ?)
					ON CONFLICT (debug_id, artifact_type) DO NOTHING
					""")
				.param(bundleId)
				.param(DebugId.normalize(debugId))
				.param(type)
				.param(filePath)
				.param(mapper.writeValueAsString(info.path("headers")))
				.param(gzip(content))
				.update();
		}
		return inserted;
	}

	private void associateReleases(long bundleId, List<String> projectSlugs, String release) {
		if (release == null || release.isBlank()) {
			return;
		}
		for (String slug : projectSlugs) {
			Optional<Long> projectId = jdbc.sql("SELECT id FROM project WHERE slug = ?")
				.param(slug)
				.query(Long.class)
				.optional();
			if (projectId.isEmpty()) {
				log.warn("assemble: unknown project slug {}, skipping release association", slug);
				continue;
			}
			jdbc.sql("INSERT INTO release (project_id, version) VALUES (?, ?) ON CONFLICT (project_id, version) DO NOTHING")
				.param(projectId.get())
				.param(release)
				.update();
			jdbc.sql("""
					INSERT INTO artifact_bundle_release (bundle_id, project_id, release)
					VALUES (?, ?, ?) ON CONFLICT DO NOTHING
					""")
				.param(bundleId)
				.param(projectId.get())
				.param(release)
				.update();
		}
	}

	/** Case-insensitive manifest header lookup — sentry-cli versions differ in casing. */
	private static String header(JsonNode headers, String name) {
		for (Map.Entry<String, JsonNode> property : headers.properties()) {
			if (property.getKey().equalsIgnoreCase(name)) {
				return property.getValue().asText(null);
			}
		}
		return null;
	}

	private static Map<String, byte[]> unzip(byte[] bundle) throws IOException {
		// sentry-cli ships a "source bundle": the zip is prefixed with an 8-byte
		// header (magic "SYSB" + u32 version) that stream-based unzipping chokes on.
		int offset = bundle.length >= 8 && bundle[0] == 'S' && bundle[1] == 'Y' && bundle[2] == 'S'
				&& bundle[3] == 'B' ? 8 : 0;
		Map<String, byte[]> files = new LinkedHashMap<>();
		try (ZipInputStream zip = new ZipInputStream(
				new ByteArrayInputStream(bundle, offset, bundle.length - offset))) {
			ZipEntry entry;
			while ((entry = zip.getNextEntry()) != null) {
				if (entry.isDirectory()) {
					continue;
				}
				byte[] content = zip.readNBytes((int) Math.min(MAX_FILE_SIZE + 1, Integer.MAX_VALUE - 8));
				if (content.length > MAX_FILE_SIZE) {
					throw new IOException("file " + entry.getName() + " exceeds size limit");
				}
				files.put(entry.getName(), content);
			}
		}
		return files;
	}

	private static byte[] gzip(byte[] content) {
		try {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			try (GZIPOutputStream gzipOut = new GZIPOutputStream(out)) {
				gzipOut.write(content);
			}
			return out.toByteArray();
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
