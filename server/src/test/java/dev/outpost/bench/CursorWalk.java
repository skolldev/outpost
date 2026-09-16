package dev.outpost.bench;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

/**
 * Walks to a deep page cursor by cursor, exactly as a user gets there, checking
 * at each step that the cursor is actually advancing.
 *
 * @param cursor the cursor that opens the page reached
 * @param depth which page that is
 * @param pageIsFull whether that page holds a full page of rows. Exact, not
 * inferred: a cursor is emitted only when a full page was returned and more rows
 * exist, so a legitimately short last page reports false rather than failing a
 * smoke run.
 */
record CursorWalk(String cursor, int depth, boolean pageIsFull) {

	record Page(List<String> ids, String nextCursor) {
	}

	/** Fetches the page a cursor opens; a {@code null} cursor asks for page 1. */
	@FunctionalInterface
	interface Pages {

		Page at(String cursor) throws Exception;

	}

	/**
	 * Walks to page {@code target}, or as deep as the data goes — stopping early is
	 * reported via {@link #depth()} and {@link #pageIsFull()} rather than thrown.
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

		// depth >= 2: page 1 plus at least one overlap comparison against it.
		assertThat(depth).as("pages available — a deep-pagination scenario needs somewhere to go")
			.isGreaterThanOrEqualTo(2);
		return new CursorWalk(openedBy, depth, pageIsFull);
	}

}
