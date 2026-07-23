package dev.outpost.query;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Small shared helpers for the query controllers. Keyset pagination and its
 * cursor codec live in {@link KeysetPage}; what remains here are the two
 * mechanics unrelated to paging that every list/detail controller needs: SQL
 * {@code IN}-clause placeholder expansion and lenient {@code jsonb} column
 * parsing.
 */
final class QuerySupport {

	private QuerySupport() {
	}

	/** {@code n} comma-separated {@code ?} placeholders for a SQL {@code IN (...)} list. */
	static String placeholders(int n) {
		return String.join(",", Collections.nCopies(n, "?"));
	}

	/**
	 * Appends {@code AND <column> IN (?, ?, …)} to {@code sql} and its bind values to
	 * {@code params} — but only when {@code values} is non-empty, so an absent or empty
	 * filter adds no constraint. Keeps the repeated {@code IN}-clause filter (project,
	 * environment, level…) in one place across the list controllers.
	 */
	static void appendInClause(StringBuilder sql, String column, Collection<?> values, List<Object> params) {
		if (values == null || values.isEmpty()) {
			return;
		}
		sql.append(" AND ").append(column).append(" IN (").append(placeholders(values.size())).append(")");
		params.addAll(values);
	}

	/**
	 * Parses a {@code jsonb} column value into a tree, falling back to an empty
	 * object on malformed or null input — a stored column should never be
	 * unparseable, so a bad value degrades the one row rather than failing the
	 * whole query.
	 */
	static JsonNode parseJson(ObjectMapper mapper, String json) {
		try {
			return mapper.readTree(json);
		}
		catch (Exception e) {
			return mapper.createObjectNode();
		}
	}
}
