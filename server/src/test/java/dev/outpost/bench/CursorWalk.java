package dev.outpost.bench;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

/**
 * The journey to a deep page: cursor by cursor, exactly as a user gets there,
 * checking at every step that the cursor is actually advancing.
 *
 * <p>The transport is the caller's — {@link Pages} is the whole dependency — so
 * the arithmetic that decides whether "page 50" is page 50 can be tested without
 * a server. It is worth testing: a walk one page short reports page 49 under a
 * "page 50" label, silently, which is the class of wrongness the rest of this
 * harness exists to eliminate.
 *
 * @param cursor the cursor that opens the page reached — what a scenario measures
 * @param depth which page that is
 * @param pageIsFull whether that page holds a full page of rows. A cursor is
 * emitted only when a full page was returned <em>and</em> more rows exist
 * ({@code KeysetPage.paginate}), so this is exact rather than inferred: the last
 * page of a dataset is legitimately short, and a scenario that asserted a full
 * page there would fail a {@code -Pbench.scale} smoke run for running out of rows.
 */
record CursorWalk(String cursor, int depth, boolean pageIsFull) {

	/** One page as the walk needs to see it. */
	record Page(List<String> ids, String nextCursor) {
	}

	/** Fetches the page a cursor opens; a {@code null} cursor asks for page 1. */
	@FunctionalInterface
	interface Pages {

		Page at(String cursor) throws Exception;

	}

	/**
	 * Walks to page {@code target}, or as deep as the data goes. Stopping early is
	 * reported rather than thrown: a smoke run at a tenth scale legitimately runs
	 * out of rows, and measuring the deepest page it reached is more useful than
	 * failing — as long as nothing downstream mistakes it for a deep one, which is
	 * what {@link #depth()} and {@link #pageIsFull()} are for.
	 */
	static CursorWalk to(int target, Pages pages) throws Exception {
		String cursor = null;
		String openedBy = null;
		List<String> previousIds = List.of();
		int depth = 0;
		boolean pageIsFull = false;

		while (depth < target) {
			Page page = pages.at(cursor);
			openedBy = cursor;
			depth++;

			PageWalk overlap = PageWalk.inspect(previousIds, page.ids());
			assertThat(overlap.advanced()).as("page %d of the walk: %s", depth, overlap.describe()).isTrue();

			pageIsFull = page.nextCursor() != null;
			if (page.nextCursor() == null) {
				break;
			}
			cursor = page.nextCursor();
			previousIds = page.ids();
		}

		// Two is the floor at which this measured anything: the scenario is not page 1,
		// and the overlap comparison above ran at least once.
		assertThat(depth).as("pages available — a deep-pagination scenario needs somewhere to go")
			.isGreaterThanOrEqualTo(2);
		return new CursorWalk(openedBy, depth, pageIsFull);
	}

}
