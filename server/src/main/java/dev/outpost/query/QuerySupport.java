package dev.outpost.query;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.util.Collections;

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
