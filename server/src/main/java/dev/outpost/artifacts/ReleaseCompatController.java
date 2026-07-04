package dev.outpost.artifacts;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code sentry-cli releases new/finalize} compatibility (§4.4): trivial
 * upserts to the release table so existing CI recipes keep working. Legacy
 * release-file and dif uploads are intentionally absent (404).
 */
@RestController
public class ReleaseCompatController {

	public record ReleaseRequest(String version, List<String> projects) {
	}

	private final JdbcClient jdbc;

	public ReleaseCompatController(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@PostMapping("/api/0/organizations/{org}/releases/")
	public ResponseEntity<?> create(@PathVariable String org, @RequestBody ReleaseRequest request) {
		if (request.version() == null || request.version().isBlank()) {
			return ResponseEntity.badRequest().body(Map.of("detail", "version required"));
		}
		upsert(request.version(), request.projects());
		return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("version", request.version()));
	}

	/** "Finalize" — we track no lifecycle, so this is create-if-absent. */
	@PutMapping("/api/0/organizations/{org}/releases/{version}/")
	public ResponseEntity<?> finalizeRelease(@PathVariable String org, @PathVariable String version,
			@RequestBody(required = false) ReleaseRequest request) {
		upsert(version, request != null ? request.projects() : null);
		return ResponseEntity.ok(Map.of("version", version));
	}

	private void upsert(String version, List<String> projectSlugs) {
		for (String slug : projectSlugs != null ? projectSlugs : List.<String>of()) {
			Optional<Long> projectId = jdbc.sql("SELECT id FROM project WHERE slug = ?")
				.param(slug)
				.query(Long.class)
				.optional();
			projectId.ifPresent(id -> jdbc
				.sql("INSERT INTO release (project_id, version) VALUES (?, ?) ON CONFLICT (project_id, version) DO NOTHING")
				.param(id)
				.param(version)
				.update());
		}
	}
}
