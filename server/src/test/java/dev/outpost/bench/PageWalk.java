package dev.outpost.bench;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The two ways a "next page" can fail to be one, as a pure function so it can be
 * tested without a server.
 *
 * <p>This is the check that decides whether a deep-pagination measurement means
 * anything. A benchmark whose cursor silently stops advancing measures page 1
 * fifty times, reports the fastest numbers in the table, and says nothing — the
 * failure looks exactly like a fast result. Neither problem is visible in a
 * latency figure, and both are trivially visible in the ids.
 */
record PageWalk(Set<String> repeatedWithinPage, Set<String> sharedWithPreviousPage) {

	static PageWalk inspect(List<String> previousIds, List<String> ids) {
		Set<String> distinct = new LinkedHashSet<>();
		Set<String> repeated = new LinkedHashSet<>();
		for (String id : ids) {
			if (!distinct.add(id)) {
				repeated.add(id);
			}
		}
		Set<String> shared = new LinkedHashSet<>(distinct);
		shared.retainAll(previousIds);
		return new PageWalk(repeated, shared);
	}

	boolean advanced() {
		return repeatedWithinPage.isEmpty() && sharedWithPreviousPage.isEmpty();
	}

	String describe() {
		if (!repeatedWithinPage.isEmpty()) {
			return "the page repeats row ids " + repeatedWithinPage;
		}
		return "the page shares row ids " + sharedWithPreviousPage
				+ " with the one before it, so the cursor is not advancing and every "
				+ "'deep page' measurement is really page 1";
	}

}
