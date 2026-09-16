package dev.outpost.pipeline;

/**
 * A Release is a named, non-blank version string — an SDK sending {@code "release":""}
 * reaches the pipeline as an empty string, not absent. The SQL equivalent
 * ({@code btrim(release) <> ''}) lives in {@code DataRetentionService}, {@code V10},
 * and {@code V13}; keep them in sync.
 */
final class Releases {

	private Releases() {
	}

	/** Whether {@code release} names a Release, as opposed to being absent or blank. */
	static boolean isNamed(String release) {
		return release != null && !release.isBlank();
	}

}
