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
 * Performance guards for the releases page. Its {@code issue_count} column was
 * structurally the same defect trace search had and {@link TraceSearchPerformanceTest}
 * locks out: a correlated aggregate over a partitioned telemetry table, evaluated
 * once per output row, with no time bound. #130 moved it to the
 * {@code issue_release_stats} rollup, where one row per (Issue, Release) makes
 * {@code count(*)} the distinct-Issue count.
 *
 * <p><b>Three different things could pass a naive version of this guard,</b> and
 * each has its own test below because none of them implies the others — and in two
 * of the three, a block count is not what settles it:
 *
 * <ol>
 * <li>A single {@code GROUP BY release} pass over {@code event} removes the
 * per-row multiplier and still reads every retained Event on every page load.
 * {@link #releaseListStaysOffTheEventTable} is the assertion that rejects it, and
 * it is the one that holds at any dataset size.
 * <li>A supporting index makes correlated probes cheap enough to fit under any
 * ceiling this fixture can honestly set, while the endpoint returns up to
 * {@link ReleaseController#pageSize()} rows. Cost cannot reject that, so
 * {@link #noCountRunsOncePerReleaseRow} rejects it on plan shape and
 * {@link #fullPageCostsWhatAOneReleasePageCosts} sits behind as a backstop.
 * <li>Counting rollup rows without scoping them to the Project reads every other
 * Project's Issues for the same version string — release versions are not unique
 * across Projects. Cost cannot reject that either, because such a plan can still
 * filter on the version and read very little;
 * {@link #aOneReleasePageIgnoresOtherProjectsRollupRows} rejects it on the
 * <em>answer</em>, against a fixture where other Projects carry hundreds of Issues
 * on the version this one has once.
 * </ol>
 *
 * <p>Exact {@code issue_count} <em>values</em> — repeated Events on one Issue,
 * Releases with no Issues, redelivery, retention, Projects sharing a version — are
 * {@code ReleaseIssueCountIntegrationTest}'s subject. A guard that only explains
 * SQL cannot tell a fast right answer from a fast wrong one.
 *
 * <p>Baselines measured 2026-08-04 against {@link TelemetrySeeder.Scale#GUARD}:
 * 40 003 events over 10 weekly partitions, 8 releases on the project, plus the two
 * fixture projects below. The seeded project's page costs <b>83 blocks</b>, against
 * 240 299 before the fix and the 15 021 a full scan of {@code event} costs. A
 * one-release page costs 132, a full 200-release page 344, and reading every
 * Project's rollup rows — 21 601 of them — costs 404.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
		properties = { "outpost.admin.email=admin@test.local", "outpost.admin.password=test-password" })
@Import(TestcontainersConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReleaseQueryPerformanceTest {

	/**
	 * <b>A tripwire on a table this query must not read, not a calibrated ceiling</b>
	 * — and the distinction is why {@link QueryGuard#assertCeilingCanFail} is not
	 * called on it. That helper asks whether a ceiling sits below the cost of reading
	 * the table the query <em>reads</em>, which is the right question for a ceiling
	 * and the wrong one here: this query reads {@code issue_release_stats} and
	 * {@code release}, neither of which {@code QueryGuard.FULL_SCAN_COLUMNS}
	 * registers, and by the rule in {@code docs/performance/measuring-retrieval.md}
	 * a query in that position gets no ceiling at all. This one has none. What it has
	 * is a bound against {@code event}, which is the table the fix removed from the
	 * plan, computed from the dataset so it means the same thing at any scale.
	 *
	 * <p>It is #130's named acceptance criterion, and it is kept even though
	 * {@link #releaseListStaysOffTheEventTable} strictly subsumes it, because the two
	 * fail differently: that one says the plan touched a table it should not have,
	 * this one says by how much. A regression to the correlated aggregate costs
	 * ~240 300 blocks against the ~15 000 a full scan costs, which is the number that
	 * made the case in the first place.
	 */
	private static final int FULL_SCANS_ALLOWED = 2;

	/**
	 * A full page may cost more than a one-Release page — it returns two hundred times
	 * the rows and counts twenty thousand memberships against one — but not
	 * <em>per-row</em> more. Measured: 344 blocks against 132, a factor of 2.6 for
	 * 200x the output.
	 *
	 * <p>The shape this rejects pays an index descent per Release instead, and it is
	 * not hypothetical — both attempts to write this query more neatly produced it.
	 * Leaving the two artifact counts correlated cost 1 731 blocks against 47 (37x,
	 * on the fixture before it seeded artifacts), and replacing each branch's bound
	 * {@code project_id = ?} with a join onto {@code page} cost 3 368 against 132
	 * (25x). Ten sits between those and the healthy 2.6 with room on both sides, which
	 * is the point: the assertion has to reject a shape, not fence in a number.
	 */
	private static final int FULL_PAGE_COST_MULTIPLE = 10;

	/**
	 * Issues on the busy project, each seen on every one of its Releases — 20 000
	 * membership rows, which is what gives {@link #aOneReleasePageIgnoresOtherProjectsRollupRows}
	 * a comparator worth clearing. On a rollup small enough to sit in a couple of
	 * blocks, "reads only this Project's rows" and "reads the table" cost the same and
	 * the assertion could not fail.
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
		// The seeder's own lesson: without statistics the planner is blind, and every
		// plan measured below is one nobody would ever get.
		jdbc.sql("""
				VACUUM ANALYZE issue, issue_release_stats, release,
				               artifact, artifact_bundle, artifact_bundle_release
				""").update();
	}

	// ------------------------------------------------------- the ticket's bound

	/**
	 * #130's named acceptance criterion, re-enabled. See {@link #FULL_SCANS_ALLOWED}
	 * for why it is kept now that the query does not read {@code event} at all.
	 */
	@Test
	void releaseListDoesNotOutcostReadingEventTwice() {
		long fullScan = QueryGuard.fullScanCost(jdbc, "event");
		PlanFacts facts = QueryPlans.releaseList(seeded.projectId()).explain(jdbc);

		assertThat(facts.logicalIo())
			.as("blocks for the release list against the %d a single full scan of event costs%n%s", fullScan,
					facts.plan())
			.isLessThan(FULL_SCANS_ALLOWED * fullScan);
	}

	// ------------------------------------------------------------ what it reads

	/**
	 * The structural claim, and the only one that survives a change of scale: the
	 * page's cost cannot grow with retention because the page does not read the
	 * retained data. A {@code GROUP BY release} over {@code event} computed once per
	 * request would satisfy every ceiling in this file and fail here, which is the
	 * distinction #130's comment asked to be made explicit.
	 */
	@Test
	void releaseListStaysOffTheEventTable() {
		PlanFacts facts = QueryPlans.releaseList(seeded.projectId()).explain(jdbc);

		assertThat(facts.relationsScanned()).as("relations read by the release list%n%s", facts.plan())
			.noneMatch(relation -> relation.startsWith("event"));
	}

	/**
	 * A page of at most {@link ReleaseController#pageSize()} rows must not spill to a
	 * temp file. A correlated plan happened not to — it was slow rather than
	 * memory-hungry — so this was the one assertion here that stayed enabled through
	 * #130, and it is what keeps the grouped rewrite honest: the aggregate now hashes
	 * rollup rows, and hashing them all instead of the page's would show up here
	 * first. Asserted on the full page, where there is something to spill.
	 */
	@Test
	void releaseListDoesNotSpillToDisk() {
		QueryGuard.assertNoTempFiles(QueryPlans.releaseList(seeded.projectId()).explain(jdbc), "the release list");
		QueryGuard.assertNoTempFiles(QueryPlans.releaseList(busyProjectId).explain(jdbc), "a full page of releases");
	}

	// -------------------------------------------------------------- what scales

	/**
	 * <b>No count may be a subquery the executor re-runs per Release row.</b> This is
	 * the assertion that actually forbids the defect, and it is separate from the
	 * costs below on purpose: #130's comment warned that "a supporting index could
	 * make eight correlated probes fit under the ceiling without removing the
	 * per-output-row structure", and it is right — a page's worth of indexed probes
	 * is cheap enough to pass any bound this fixture can honestly set. Cost cannot
	 * express "not once per row"; plan shape can.
	 *
	 * <p>Asserted on the full page, since a fixture with eight Releases is where a
	 * per-row plan hides best.
	 */
	@Test
	void noCountRunsOncePerReleaseRow() {
		PlanFacts facts = warm(QueryPlans.releaseList(busyProjectId));

		assertThat(facts.correlatedSubplans())
			.as("subqueries re-run per output row by a %d-release page%n%s", ReleaseController.pageSize(), facts.plan())
			.isEmpty();
	}

	/**
	 * Work must scale with the page, not with the correlation. The guard dataset has
	 * eight Releases and the endpoint returns two hundred, so a per-row aggregate
	 * cheap enough to hide at eight is the fixture-shaped result #130's comment
	 * warned the old bound would certify.
	 *
	 * <p>This is the backstop, not the structural claim —
	 * {@link #noCountRunsOncePerReleaseRow} is that. The two fixtures differ in
	 * Releases <em>and</em> in memberships (one against twenty thousand), so the ratio
	 * confounds the two and is deliberately loose; what it catches is a plan whose
	 * cost per output row grew, whatever the reason.
	 *
	 * <p>The counts are read back, not just explained. {@code EXPLAIN ANALYZE}
	 * executes the query but asserts nothing about what came out of it, and a plan
	 * that is fast because it found nothing is the other way to pass this.
	 */
	@Test
	void fullPageCostsWhatAOneReleasePageCosts() {
		long oneReleaseBlocks = warm(QueryPlans.releaseList(soloProjectId)).logicalIo();
		QueryPlans.Built page = QueryPlans.releaseList(busyProjectId);
		PlanFacts facts = warm(page);

		List<Map<String, Object>> rows = page.rows(jdbc);
		assertThat(rows).as("rows on a full release page — a page that returns nothing is fast and meaningless")
			.hasSize(ReleaseController.pageSize());
		// All three counts, because all three were correlated and all three were
		// rewritten. A plan that returned the right issue_count beside a zeroed
		// artifact_count would be cheap for the wrong reason and pass the bound below.
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
	 * Work must scale with the Project, not with the installation. Release versions
	 * are not unique across Projects — {@code release} is keyed
	 * {@code (project_id, version)} and every install names its releases the same
	 * handful of ways — so a count that groups the rollup without scoping it reads
	 * every other Project's memberships and then discards them.
	 *
	 * <p><b>The value is what proves it, not the block count.</b> A cost bound cannot:
	 * a plan that dropped {@code project_id} but kept matching on the version string
	 * would read only the rows carrying <em>that one version</em> — a few dozen — and
	 * sit comfortably under any ceiling while returning a number that belongs to the
	 * whole installation. So the fixture is built to make that plan wrong rather than
	 * slow: the solo Project's single Release shares its version with the busy
	 * Project's, where {@link #BUSY_PROJECT_ISSUES} Issues carry it, and with the
	 * seeded Projects' hundreds. One Issue is the answer; anything project-blind
	 * returns three figures.
	 *
	 * <p>The block bound stays behind it, against what reading the whole rollup
	 * costs — the floor a plan that grouped every Project's memberships would pay.
	 * The busy project's twenty thousand rows are there to make that number worth
	 * clearing: without them the rollup fits in a couple of blocks and it could not
	 * fail. Measured 132 blocks against 404.
	 */
	@Test
	void aOneReleasePageIgnoresOtherProjectsRollupRows() {
		QueryPlans.Built page = QueryPlans.releaseList(soloProjectId);
		List<Map<String, Object>> rows = page.rows(jdbc);

		assertThat(rows).as("the solo project's page").hasSize(1);
		assertThat(rows.getFirst().get("issue_count"))
			.as("issue_count for a version %d other issues carry in another project", BUSY_PROJECT_ISSUES)
			.isEqualTo(1L);

		// count(event_count), not count(release): `release` is the trailing column of
		// idx_issue_release_stats_project_release, so counting it can be answered
		// index-only and would price "read every project's rows" below what reading
		// them costs. QueryGuard.FULL_SCAN_COLUMNS makes the same choice for the
		// telemetry tables, and for the same reason.
		long wholeRollup = warm(new QueryPlans.Built("SELECT count(event_count) FROM issue_release_stats", List.of()))
			.logicalIo();
		PlanFacts facts = warm(page);

		assertThat(facts.logicalIo())
			.as("blocks for a 1-release page against the %d reading every project's rollup rows costs%n%s", wholeRollup,
					facts.plan())
			.isLessThan(wholeRollup);
	}

	// ----------------------------------------------------------------- measuring

	/**
	 * {@code EXPLAIN}s twice and keeps the second, for the same reason the seeder ends
	 * in {@code VACUUM ANALYZE} rather than {@code ANALYZE}: a number that depends on
	 * whether its test ran first is not a measurement of the query.
	 *
	 * <p>The seeder's problem was hint bits on a freshly loaded heap. This one is the
	 * planner's own reads — {@code PlanFacts} sums the {@code Planning} node's buffers
	 * alongside the executed ones, and planning a four-branch statement for the first
	 * time in a session pulls catalog pages that are cached ever after. The gap is not
	 * small: this page measured 270 blocks cold and 132 warm, so which of two
	 * comparisons ran first decided whether a guard passed. Both sides of every
	 * comparison below are read warm.
	 */
	private PlanFacts warm(QueryPlans.Built built) {
		built.explain(jdbc);
		return built.explain(jdbc);
	}

	// ----------------------------------------------------------------- fixtures

	/**
	 * A Project whose Releases exist only in the rollup — Issues and memberships, no
	 * Events. Deliberately: the claim under test is that the page is answered from
	 * {@code issue_release_stats}, and a fixture that seeded Events could not tell a
	 * plan reading the rollup from one reading around it.
	 *
	 * <p>Uploaded artifacts <em>are</em> seeded, and that is not decoration. The page
	 * carries three counts and all three were correlated; a fixture with no artifact
	 * rows leaves the two artifact branches probing an empty index, which costs about
	 * a block a row and slips under
	 * {@link #FULL_PAGE_COST_MULTIPLE} even when re-correlated. The scaling guard
	 * would then fence {@code issue_count} alone and quietly certify the other two.
	 *
	 * @return the new project's id
	 */
	private long seedRollupOnly(String slug, int releaseCount, int issueCount) {
		long projectId = jdbc.sql("INSERT INTO project (slug, name) VALUES (?, ?) RETURNING id")
			.param(slug)
			.param(slug)
			.query(Long.class)
			.single();
		// Same version strings the seeded projects use, so a plan that matches on
		// version alone finds the other projects' rows rather than nothing.
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
		// The checksum carries the Release it was uploaded for, so the link below can
		// join back to it. `checksum` is globally unique, hence the project prefix.
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
