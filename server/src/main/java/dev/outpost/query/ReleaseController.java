package dev.outpost.query;

import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Release + uploaded-artifact inspection (§8): the Releases page is primarily
 * a "why isn't my stack trace symbolicated" debugging aid (§9.5).
 */
@RestController
@RequestMapping("/api/internal/releases")
public class ReleaseController {

	public record Release(long id, String version, Instant createdAt, long bundleCount, long artifactCount,
			long issueCount) {
	}

	public record Artifact(long id, String debugId, String artifactType, String filePath, long sizeBytes,
			String bundleChecksum, Instant uploadedAt) {
	}

	private final JdbcClient jdbc;

	public ReleaseController(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@GetMapping
	public List<Release> list(@RequestParam long project) {
		return jdbc.sql("""
				SELECT r.id, r.version, r.created_at,
				       (SELECT count(*) FROM artifact_bundle_release abr
				        WHERE abr.project_id = r.project_id AND abr.release = r.version) AS bundle_count,
				       (SELECT count(*) FROM artifact a
				        JOIN artifact_bundle_release abr ON abr.bundle_id = a.bundle_id
				        WHERE abr.project_id = r.project_id AND abr.release = r.version) AS artifact_count,
				       (SELECT count(DISTINCT e.issue_id) FROM event e
				        WHERE e.project_id = r.project_id AND e.release = r.version) AS issue_count
				FROM release r
				WHERE r.project_id = ?
				ORDER BY r.created_at DESC, r.id DESC
				LIMIT 200
				""")
			.param(project)
			.query((rs, i) -> new Release(rs.getLong("id"), rs.getString("version"),
					rs.getTimestamp("created_at").toInstant(), rs.getLong("bundle_count"),
					rs.getLong("artifact_count"), rs.getLong("issue_count")))
			.list();
	}

	@GetMapping("/{version}/artifacts")
	public List<Artifact> artifacts(@PathVariable String version, @RequestParam long project) {
		return jdbc.sql("""
				SELECT a.id, a.debug_id, a.artifact_type, a.file_path, octet_length(a.content) AS size_bytes,
				       b.checksum, b.created_at
				FROM artifact a
				JOIN artifact_bundle b ON b.id = a.bundle_id
				JOIN artifact_bundle_release abr ON abr.bundle_id = a.bundle_id
				WHERE abr.project_id = ? AND abr.release = ?
				ORDER BY a.file_path, a.artifact_type
				""")
			.param(project)
			.param(version)
			.query((rs, i) -> new Artifact(rs.getLong("id"), rs.getString("debug_id"), rs.getString("artifact_type"),
					rs.getString("file_path"), rs.getLong("size_bytes"), rs.getString("checksum"),
					rs.getTimestamp("created_at").toInstant()))
			.list();
	}
}
