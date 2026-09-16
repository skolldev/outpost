package dev.outpost.query;

import java.util.List;

/**
 * A built query and its ordered bind params, returned by the {@code build…Query} factories on
 * the query controllers. Exists so performance guards can {@code EXPLAIN} the controller's own
 * SQL rather than a copy that could drift from it and keep passing after the real query
 * regresses.
 */
record SearchQuery(String sql, List<Object> params) {
}
