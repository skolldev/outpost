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
 * Release + uploaded-artifact inspection: the Releases page is primarily
 * a "why isn't my stack trace symbolicated" debugging aid.
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

	private static final int PAGE_SIZE = 200;

	private final JdbcClient jdbc;

	public ReleaseController(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** How many Releases one page carries — the multiplier any per-row work pays. */
	static int pageSize() {
		return PAGE_SIZE;
	}

	@GetMapping
	public List<Release> list(@RequestParam long project) {
		SearchQuery search = buildReleaseListQuery(project);
		return jdbc.sql(search.sql())
			.params(search.params())
			.query((rs, i) -> new Release(rs.getLong("id"), rs.getString("version"),
					rs.getTimestamp("created_at").toInstant(), rs.getLong("bundle_count"),
					rs.getLong("artifact_count"), rs.getLong("issue_count")))
			.list();
	}

	/**
	 * The release-list query the controller runs, extracted per {@link SearchQuery}.
	 * {@code issue_count} counts distinct Issues with a retained Event on that
	 * Release, from {@code issue_release_stats} rather than {@code event} (cost
	 * tracks Issues/Releases, not Event retention, #130); the Project is bound
	 * into each branch rather than joined out of the {@code page} CTE, since
	 * joining it out turns the grouped passes into a per-Release nested loop
	 * ({@code ReleaseQueryPerformanceTest} guards against this).
	 */
	static SearchQuery buildReleaseListQuery(long project) {
		return new SearchQuery("""
				WITH page AS (
				    SELECT r.id, r.project_id, r.version, r.created_at
				    FROM release r
				    WHERE r.project_id = ?
				    ORDER BY r.created_at DESC, r.id DESC
				    LIMIT %d
				), bundles AS (
				    SELECT abr.release, count(*) AS bundle_count
				    FROM artifact_bundle_release abr
				    WHERE abr.project_id = ? AND abr.release IN (SELECT version FROM page)
				    GROUP BY abr.release
				), artifacts AS (
				    SELECT abr.release, count(*) AS artifact_count
				    FROM artifact a
				    JOIN artifact_bundle_release abr ON abr.bundle_id = a.bundle_id
				    WHERE abr.project_id = ? AND abr.release IN (SELECT version FROM page)
				    GROUP BY abr.release
				), issue_counts AS (
				    SELECT s.release, count(*) AS issue_count
				    FROM issue_release_stats s
				    WHERE s.project_id = ? AND s.release IN (SELECT version FROM page)
				    GROUP BY s.release
				)
				SELECT p.id, p.version, p.created_at,
				       coalesce(b.bundle_count, 0) AS bundle_count,
				       coalesce(a.artifact_count, 0) AS artifact_count,
				       coalesce(c.issue_count, 0) AS issue_count
				FROM page p
				LEFT JOIN bundles b ON b.release = p.version
				LEFT JOIN artifacts a ON a.release = p.version
				LEFT JOIN issue_counts c ON c.release = p.version
				ORDER BY p.created_at DESC, p.id DESC
				""".formatted(PAGE_SIZE), List.of(project, project, project, project));
	}

	/**
	 * The most recently created release versions of every Project at once, for the
	 * MCP Surface's {@code list_projects}: an agent filters by exact version string
	 * and cannot guess one, so the catalogue call hands out the recent ones the way
	 * it hands out Environment Names. Capped per Project in SQL since a Project can
	 * hold thousands of Releases and the caller wants the newest handful, not a page of each.
	 */
	static SearchQuery buildRecentReleasesQuery(int perProject) {
		return new SearchQuery("""
				SELECT project_id, version FROM (
				    SELECT project_id, version,
				           row_number() OVER (PARTITION BY project_id ORDER BY created_at DESC, id DESC) AS rn
				    FROM release
				) r WHERE rn <= ?
				ORDER BY project_id, rn
				""", List.of(perProject));
	}

	/**
	 * Whether any Project has a Release of this exact version, for the MCP
	 * Surface's release-filter validation. Installation-wide on purpose: a version
	 * that exists on another Project than the one filtered is a legitimate empty
	 * answer, where a version that exists nowhere is a typo worth refusing.
	 */
	static SearchQuery buildKnownReleaseQuery(String version) {
		return new SearchQuery("SELECT count(*) FROM release WHERE version = ?", List.of(version));
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
