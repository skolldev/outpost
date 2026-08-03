package dev.outpost.pipeline;

/**
 * What counts as a Release on the ingest path.
 *
 * <p>A Release is "a named version of one Project" (CONTEXT.md), and the rule that
 * matters here is the degenerate case: an SDK sending {@code "release":""} reaches
 * the pipelines as an empty string rather than as absent. Every part of the system
 * already refuses to treat that as a Release — {@code IssueController} rejects a
 * blank release filter, {@code ReleaseCompatController} refuses to create one, and
 * since #130 the Releases page reads its Issue counts from a rollup that has no row
 * for one — but each said so in its own words, and the copies had already drifted.
 * This is the Java half of that rule in one place.
 *
 * <p>The SQL half cannot share it and is written {@code btrim(release) <> ''} in
 * {@code DataRetentionService}, {@code V10} and {@code V13}. Those are the sites to
 * change with this one.
 */
final class Releases {

	private Releases() {
	}

	/** Whether {@code release} names a Release, as opposed to being absent or blank. */
	static boolean isNamed(String release) {
		return release != null && !release.isBlank();
	}

}
