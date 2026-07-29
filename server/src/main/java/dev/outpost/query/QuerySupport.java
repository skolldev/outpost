package dev.outpost.query;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Shared helpers for the query controllers. Paging lives in {@link KeysetPage};
 * what stays here is the paging-independent mechanics every list/detail
 * controller needs.
 */
final class QuerySupport {

	private QuerySupport() {
	}

	/** {@code n} comma-separated {@code ?} placeholders for a SQL {@code IN (...)} list. */
	static String placeholders(int n) {
		return String.join(",", Collections.nCopies(n, "?"));
	}

	/**
	 * Appends {@code AND <column> IN (?, ?, …)} and its bind values — but only when
	 * {@code values} is non-empty, so an absent or empty filter adds no constraint.
	 */
	static void appendInClause(StringBuilder sql, String column, Collection<?> values, List<Object> params) {
		if (values == null || values.isEmpty()) {
			return;
		}
		sql.append(" AND ").append(column).append(" IN (").append(placeholders(values.size())).append(")");
		params.addAll(values);
	}

	/**
	 * Falls back to an empty object on malformed or null input: a stored column
	 * should never be unparseable, so a bad value degrades that one row rather than
	 * failing the whole query.
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
