package dev.outpost.artifacts;

import dev.outpost.config.OutpostProperties;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

/**
 * sentry-cli chunk upload (§4.4): capability discovery + chunk staging.
 * {@code {org}} is accepted but ignored (single tenant). The CLI hashes each
 * chunk with SHA-1 (part filename), optionally gzips the part body (part name
 * {@code file_gzip} instead of {@code file}), and re-uploads anything the
 * assemble endpoint reports missing.
 */
@RestController
public class ChunkUploadController {

	static final int CHUNK_SIZE = 8 * 1024 * 1024;
	static final int MAX_REQUEST_SIZE = 32 * 1024 * 1024;

	private final ArtifactBundleService artifacts;
	private final OutpostProperties properties;

	public ChunkUploadController(ArtifactBundleService artifacts, OutpostProperties properties) {
		this.artifacts = artifacts;
		this.properties = properties;
	}

	/**
	 * Capability discovery. {@code accept: ["artifact_bundles"]} steers the CLI
	 * to the debug-ID flow; camelCase keys are part of the wire contract, hence
	 * the explicit map (the app default is snake_case).
	 */
	@GetMapping("/api/0/organizations/{org}/chunk-upload/")
	public Map<String, Object> capabilities(@PathVariable String org) {
		Map<String, Object> response = new LinkedHashMap<>();
		response.put("url", properties.publicUrl() + "/api/0/organizations/" + org + "/chunk-upload/");
		response.put("chunkSize", CHUNK_SIZE);
		response.put("chunksPerRequest", 64);
		response.put("maxFileSize", MAX_REQUEST_SIZE);
		response.put("maxRequestSize", MAX_REQUEST_SIZE);
		response.put("concurrency", 4);
		response.put("hashAlgorithm", "sha1");
		response.put("accept", List.of("artifact_bundles"));
		response.put("compression", List.of("gzip"));
		return response;
	}

	@PostMapping("/api/0/organizations/{org}/chunk-upload/")
	public ResponseEntity<?> upload(@PathVariable String org, MultipartHttpServletRequest request) throws IOException {
		for (MultipartFile part : request.getFiles("file")) {
			ResponseEntity<?> error = stage(part, false);
			if (error != null) {
				return error;
			}
		}
		for (MultipartFile part : request.getFiles("file_gzip")) {
			ResponseEntity<?> error = stage(part, true);
			if (error != null) {
				return error;
			}
		}
		return ResponseEntity.ok(Map.of());
	}

	private ResponseEntity<?> stage(MultipartFile part, boolean gzipped) throws IOException {
		String expectedSha1 = part.getOriginalFilename();
		if (expectedSha1 == null || !expectedSha1.matches("[0-9a-fA-F]{40}")) {
			return ResponseEntity.badRequest().body(Map.of("error", "chunk filename must be its sha1"));
		}
		byte[] content;
		if (gzipped) {
			try (InputStream in = new GZIPInputStream(part.getInputStream())) {
				content = in.readNBytes(CHUNK_SIZE + 1);
			}
		}
		else {
			content = part.getBytes();
		}
		if (content.length > CHUNK_SIZE) {
			return ResponseEntity.badRequest().body(Map.of("error", "chunk exceeds chunkSize"));
		}
		if (!sha1(content).equals(expectedSha1.toLowerCase(Locale.ROOT))) {
			return ResponseEntity.badRequest().body(Map.of("error", "chunk checksum mismatch"));
		}
		artifacts.stageChunk(expectedSha1.toLowerCase(Locale.ROOT), content);
		return null;
	}

	static String sha1(byte[] data) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(data));
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
