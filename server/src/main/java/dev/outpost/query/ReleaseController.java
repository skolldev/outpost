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
	 *
	 * <p>{@code issue_count} is the number of distinct Issues carrying at least one
	 * <em>retained</em> Event on that Release — not the number ever seen on it. The
	 * distinction is invisible until the Data Retention Policy expires an Event, at
	 * which point the count drops; that was true of the aggregate over {@code event}
	 * too, which could only count rows that still existed, and the rollup preserves it
	 * because {@code DataRetentionService} rebuilds an affected Issue's rows from the
	 * Events that survived the sweep.
	 *
	 * <p>It used to be {@code count(DISTINCT e.issue_id)} over {@code event},
	 * correlated to the Release row and carrying no time bound — a fresh aggregate
	 * over every weekly partition for each of up to {@link #PAGE_SIZE} rows, which
	 * cost 16x a full scan of {@code event} to annotate eight Releases and 14.9
	 * seconds at benchmark scale (#130).
	 *
	 * <p>It is now counted from {@code issue_release_stats}, which holds exactly one
	 * row per (Issue, Release) with a retained Event — so {@code count(*)}
	 * over a Release's rows <em>is</em> its distinct-Issue count, without a
	 * {@code DISTINCT} and without reading a telemetry table at all. That is the
	 * property that matters: the page's cost is a function of the Project's Issues
	 * and Releases, not of how long Events are retained.
	 *
	 * <p>Two shapes here are load-bearing rather than stylistic.
	 * {@code page} is materialized — Postgres materializes a CTE referenced more
	 * than once — so the Releases are chosen once and every count is looked up for
	 * that page rather than for every Release the Project has ever had. And each
	 * count is one grouped pass joined onto the page, never a subquery per row: an
	 * index makes {@link #PAGE_SIZE} correlated probes cheap enough to hide under any
	 * ceiling a guard fixture can set, but it leaves in place the shape that made this
	 * a bug.
	 *
	 * <p><b>The Project is bound into each branch rather than joined out of
	 * {@code page}, and that repetition is deliberate.</b> Replacing
	 * {@code project_id = ?} with {@code JOIN page p ON p.project_id = …} reads better
	 * and binds the parameter once, and it was tried: the planner stops seeing a
	 * constant it can open one index range with, drives the join from {@code page}
	 * instead, and turns all three grouped passes back into a nested loop probing once
	 * per Release. A full page went from 157 blocks to 3 368 — the same per-output-row
	 * shape this whole change exists to remove, reintroduced by a tidier-looking query.
	 * {@code ReleaseQueryPerformanceTest.fullPageCostsWhatAOneReleasePageCosts} is what
	 * caught it, and is what will catch it again.
	 *
	 * <p>That is why the two artifact counts were rewritten as well. They read
	 * low-volume, unpartitioned tables and were never the defect — but they were
	 * correlated, and measured on a full page they were 8 blocks per row of the 1 731
	 * the page cost once {@code issue_count} stopped dominating everything. Leaving
	 * them would have meant a guard that could not honestly assert the page's cost
	 * is a function of the page.
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
