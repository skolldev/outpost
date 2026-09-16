package dev.outpost.query;

import static org.assertj.core.api.Assertions.assertThat;

import dev.outpost.TestcontainersConfiguration;
import dev.outpost.db.PartitionManager;
import dev.outpost.support.PlanFacts;
import dev.outpost.support.TelemetrySeeder;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Performance guards for the releases page's {@code issue_count},
 * {@code bundle_count} and {@code artifact_count} columns, answered from the
 * {@code issue_release_stats} rollup rather than a correlated aggregate over
 * {@code event}. Cost alone can't reject every regression shape — a
 * per-row-cheap-enough correlated count, or an unscoped rollup read across
 * Projects — so plan shape and answer values are asserted too, with exact
 * {@code issue_count} correctness left to {@code ReleaseIssueCountIntegrationTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
		properties = { "outpost.admin.email=admin@test.local", "outpost.admin.password=test-password" })
@Import(TestcontainersConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReleaseQueryPerformanceTest {

	/**
	 * A tripwire against {@code event}, not a calibrated ceiling — this query
	 * reads {@code issue_release_stats} and {@code release} instead, neither of
	 * which {@link QueryGuard#assertCeilingCanFail} can validate a ceiling
	 * against. Kept alongside {@link #releaseListStaysOffTheEventTable} because
	 * the two fail differently: that one says a table was touched at all, this
	 * one says by how much.
	 */
	private static final int FULL_SCANS_ALLOWED = 2;

	/**
	 * A full page returns 200x the rows of a one-Release page but must not cost
	 * 200x more (measured: 344 blocks against 132, a 2.6x factor). Ten leaves
	 * headroom on both sides of that 2.6x while still rejecting a per-Release
	 * index descent, which is the shape this exists to catch.
	 */
	private static final int FULL_PAGE_COST_MULTIPLE = 10;

	/**
	 * Issues on the busy project, each seen on every Release — 20 000 membership
	 * rows, big enough that {@link #aOneReleasePageIgnoresOtherProjectsRollupRows}
	 * has a comparator worth clearing. On a rollup small enough to fit in a couple
	 * of blocks, "reads only this Project" and "reads the table" cost the same and
	 * the assertion couldn't fail.
	 */
	private static final int BUSY_PROJECT_ISSUES = 100;

	/** Files in each Release's uploaded bundle, so the artifact counts have rows to count. */
	private static final int ARTIFACTS_PER_BUNDLE = 4;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	PartitionManager partitions;

	TelemetrySeeder.Seeded seeded;

	/** One Release, one Issue — the smallest page the endpoint can return. */
	long soloProjectId;

	/** A full page of Releases, every one of them carrying every Issue. */
	long busyProjectId;

	@BeforeAll
	void seed() {
		seeded = new TelemetrySeeder(jdbc, partitions).seed(TelemetrySeeder.Scale.GUARD);
		soloProjectId = seedRollupOnly("solo", 1, 1);
		busyProjectId = seedRollupOnly("busy", ReleaseController.pageSize(), BUSY_PROJECT_ISSUES);
		// Without ANALYZE the planner is blind and every plan below would be unrealistic.
		jdbc.sql("""
				VACUUM ANALYZE issue, issue_release_stats, release,
				               artifact, artifact_bundle, artifact_bundle_release
				""").update();
	}

	@Test
	void releaseListDoesNotOutcostReadingEventTwice() {
		long fullScan = QueryGuard.fullScanCost(jdbc, "event");
		PlanFacts facts = QueryPlans.releaseList(seeded.projectId()).explain(jdbc);

		assertThat(facts.logicalIo())
			.as("blocks for the release list against the %d a single full scan of event costs%n%s", fullScan,
					facts.plan())
			.isLessThan(FULL_SCANS_ALLOWED * fullScan);
	}

	/**
	 * The page's cost cannot grow with retention because it never reads the
	 * retained data. A {@code GROUP BY release} over {@code event} computed once
	 * per request would satisfy every buffer ceiling in this file and fail only here.
	 */
	@Test
	void releaseListStaysOffTheEventTable() {
		PlanFacts facts = QueryPlans.releaseList(seeded.projectId()).explain(jdbc);

		assertThat(facts.relationsScanned()).as("relations read by the release list%n%s", facts.plan())
			.noneMatch(relation -> relation.startsWith("event"));
	}

	/**
	 * A page of at most {@link ReleaseController#pageSize()} rows must not spill to
	 * a temp file. The grouped rewrite hashes rollup rows, so hashing all of them
	 * instead of just the page's would show up here first; asserted on the full
	 * page, where there's something to spill.
	 */
	@Test
	void releaseListDoesNotSpillToDisk() {
		QueryGuard.assertNoTempFiles(QueryPlans.releaseList(seeded.projectId()).explain(jdbc), "the release list");
		QueryGuard.assertNoTempFiles(QueryPlans.releaseList(busyProjectId).explain(jdbc), "a full page of releases");
	}

	/**
	 * No count may be a subquery the executor re-runs per Release row — a
	 * supporting index could make per-row probes cheap enough to pass any cost
	 * bound, so this asserts plan shape instead. Asserted on the full page, since
	 * a fixture with eight Releases is where a per-row plan hides best.
	 */
	@Test
	void noCountRunsOncePerReleaseRow() {
		PlanFacts facts = warm(QueryPlans.releaseList(busyProjectId));

		assertThat(facts.correlatedSubplans())
			.as("subqueries re-run per output row by a %d-release page%n%s", ReleaseController.pageSize(), facts.plan())
			.isEmpty();
	}

	/**
	 * The backstop, not the structural claim ({@link #noCountRunsOncePerReleaseRow}
	 * is that) — deliberately loose since the two fixtures differ in Releases and
	 * in memberships. Rows are read back, not just explained, because a plan that
	 * is fast for finding nothing would otherwise pass.
	 */
	@Test
	void fullPageCostsWhatAOneReleasePageCosts() {
		long oneReleaseBlocks = warm(QueryPlans.releaseList(soloProjectId)).logicalIo();
		QueryPlans.Built page = QueryPlans.releaseList(busyProjectId);
		PlanFacts facts = warm(page);

		List<Map<String, Object>> rows = page.rows(jdbc);
		assertThat(rows).as("rows on a full release page — a page that returns nothing is fast and meaningless")
			.hasSize(ReleaseController.pageSize());
		// All three counts, since a right issue_count beside a zeroed artifact_count would pass wrongly.
		assertThat(rows).allSatisfy(row -> {
			assertThat(row.get("issue_count"))
				.as("issue_count on a full release page, each of whose releases carries every issue")
				.isEqualTo((long) BUSY_PROJECT_ISSUES);
			assertThat(row.get("bundle_count")).as("bundle_count — one uploaded bundle per release").isEqualTo(1L);
			assertThat(row.get("artifact_count")).as("artifact_count — the files in that bundle")
				.isEqualTo((long) ARTIFACTS_PER_BUNDLE);
		});
		assertThat(facts.logicalIo())
			.as("blocks for a %d-release page against the %d a 1-release page costs%n%s", ReleaseController.pageSize(),
					oneReleaseBlocks, facts.plan())
			.isLessThan(FULL_PAGE_COST_MULTIPLE * oneReleaseBlocks);
	}

	/**
	 * Release versions aren't unique across Projects, so a rollup count that drops
	 * {@code project_id} but matches on the version string would still read few
	 * rows and sit under any cost ceiling — the returned value is what catches
	 * that, not the block count. The fixture makes the solo Project's
	 * Release share its version with the busy Project's {@link #BUSY_PROJECT_ISSUES}
	 * Issues, so a project-blind count returns three figures instead of one.
	 */
	@Test
	void aOneReleasePageIgnoresOtherProjectsRollupRows() {
		QueryPlans.Built page = QueryPlans.releaseList(soloProjectId);
		List<Map<String, Object>> rows = page.rows(jdbc);

		assertThat(rows).as("the solo project's page").hasSize(1);
		assertThat(rows.getFirst().get("issue_count"))
			.as("issue_count for a version %d other issues carry in another project", BUSY_PROJECT_ISSUES)
			.isEqualTo(1L);

		// count(event_count), not count(release) — release is index-only via idx_issue_release_stats_project_release and would underprice this comparison.
		long wholeRollup = warm(new QueryPlans.Built("SELECT count(event_count) FROM issue_release_stats", List.of()))
			.logicalIo();
		PlanFacts facts = warm(page);

		assertThat(facts.logicalIo())
			.as("blocks for a 1-release page against the %d reading every project's rollup rows costs%n%s", wholeRollup,
					facts.plan())
			.isLessThan(wholeRollup);
	}

	/**
	 * {@code EXPLAIN}s twice and keeps the second: {@link PlanFacts} sums the
	 * {@code Planning} node's buffers too, and planning a four-branch statement
	 * cold pulls catalog pages that stay cached afterward (270 blocks cold, 132
	 * warm here). Both sides of every comparison below must be read warm, or which
	 * ran first would decide whether the guard passes.
	 */
	private PlanFacts warm(QueryPlans.Built built) {
		built.explain(jdbc);
		return built.explain(jdbc);
	}

	/**
	 * A Project whose Releases exist only in the rollup (no Events), so a plan
	 * reading around {@code issue_release_stats} can't hide behind one reading the
	 * telemetry it usually pairs with. Artifacts are seeded too, since a fixture
	 * with no artifact rows would let the artifact-count branches probe an empty
	 * index cheaply enough to hide a per-row regression there.
	 *
	 * @return the new project's id
	 */
	private long seedRollupOnly(String slug, int releaseCount, int issueCount) {
		long projectId = jdbc.sql("INSERT INTO project (slug, name) VALUES (?, ?) RETURNING id")
			.param(slug)
			.param(slug)
			.query(Long.class)
			.single();
		// Same version strings the seeded projects use, so a version-only match finds their rows too.
		jdbc.sql("""
				INSERT INTO release (project_id, version, created_at)
				SELECT ?, 'app@1.0.' || g, now() - make_interval(days => g)
				FROM generate_series(1, ?) g
				""").param(projectId).param(releaseCount).update();
		jdbc.sql("""
				INSERT INTO issue (project_id, fingerprint, title, culprit, level, status, first_seen, last_seen)
				SELECT ?, 'fp-' || g, 'seeded', 'seeded', 'error', 'unresolved', now(), now()
				FROM generate_series(1, ?) g
				""").param(projectId).param(issueCount).update();
		jdbc.sql("""
				INSERT INTO issue_release_stats (issue_id, project_id, release, event_count, last_seen)
				SELECT i.id, i.project_id, r.version, 1, now()
				FROM issue i JOIN release r ON r.project_id = i.project_id
				WHERE i.project_id = ?
				""").param(projectId).update();
		seedArtifacts(projectId);
		return projectId;
	}

	/**
	 * One uploaded bundle per Release, each holding {@link #ARTIFACTS_PER_BUNDLE}
	 * files — a source map and its minified source per lazy chunk, which is what a
	 * front-end deploy uploads.
	 */
	private void seedArtifacts(long projectId) {
		// checksum is globally unique, hence the project prefix — the link below joins back on it.
		jdbc.sql("""
				INSERT INTO artifact_bundle (checksum, raw)
				SELECT ? || ':' || r.version, '\\x00'::bytea
				FROM release r WHERE r.project_id = ?
				""").param(projectId).param(projectId).update();
		jdbc.sql("""
				INSERT INTO artifact_bundle_release (bundle_id, project_id, release)
				SELECT b.id, ?, split_part(b.checksum, ':', 2)
				FROM artifact_bundle b WHERE b.checksum LIKE ? || ':%'
				""").param(projectId).param(projectId).update();
		jdbc.sql("""
				INSERT INTO artifact (bundle_id, debug_id, artifact_type, file_path, content)
				SELECT abr.bundle_id, gen_random_uuid()::text, 'source_map', 'main-' || g || '.js.map', '\\x00'::bytea
				FROM artifact_bundle_release abr CROSS JOIN generate_series(1, ?) g
				WHERE abr.project_id = ?
				""").param(ARTIFACTS_PER_BUNDLE).param(projectId).update();
	}

}
