package dev.outpost.query;

import java.util.List;

/**
 * A built query and its ordered bind params, returned by the {@code build…Query}
 * factories on the query controllers.
 *
 * <p>It exists so the performance guards can {@code EXPLAIN} the <b>controller's
 * own SQL</b> rather than a copy: a guard holding its own copy of the statement
 * keeps passing after the real query regresses, which is the one failure mode a
 * regression guard cannot afford. Controllers execute exactly what these
 * factories return — there is no second copy anywhere.
 */
record SearchQuery(String sql, List<Object> params) {
}
