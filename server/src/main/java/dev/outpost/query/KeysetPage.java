package dev.outpost.query;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * Keyset pagination for the query list endpoints: pages ordered by
 * {@code (sort, id)} descending, navigated by an opaque cursor — the base64url
 * token {@code sortValue|id} of the previous page's last row.
 *
 * <p>Keyset over offset so page cost stays O(page size) at any depth, via the
 * {@code (sort, id) < (?, ?)} row comparison against an index on those columns.
 *
 * <p>The tail is returned as SQL for the caller to append to its own filter
 * {@code WHERE}, rather than this class owning the whole query: the readable
 * per-endpoint {@code WHERE} stays in the controller and the exact SQL remains
 * {@code EXPLAIN}-able without a database. Enrichment (e.g. sparklines) is
 * layered on the returned rows by the caller, so it never enters this interface.
 *
 * <p>Each {@link KeyColumn} owns its cursor codec, so the typed parse logic that
 * was once copied across controllers lives in one place.
 */
final class KeysetPage {

	/**
	 * One column of a keyset, paired with the codec that moves its value across
	 * the cursor boundary.
	 *
	 * @param column the SQL identifier used in the predicate and {@code ORDER BY};
	 * may be quoted, e.g. {@code "timestamp"}
	 * @param key the result-row map key {@link #paginate} reads to build the next
	 * cursor. Differs from {@code column} only when the SQL name needs quoting,
	 * hence the explicit-{@code key} factory overloads.
	 */
	record KeyColumn(String column, String key, Function<String, Object> bind, Function<Object, String> format) {

		/** A {@code timestamptz} column: cursor holds the ISO instant, bound as a {@link Timestamp}. */
		static KeyColumn instant(String column) {
			return instant(column, column);
		}

		/** A {@code timestamptz} column whose SQL identifier differs from the result-map key. */
		static KeyColumn instant(String column, String key) {
			return new KeyColumn(column, key, part -> Timestamp.from(Instant.parse(part)), Object::toString);
		}

		/** A {@code bigint} column (an identity id or an event count). */
		static KeyColumn longs(String column) {
			return new KeyColumn(column, column, Long::parseLong, String::valueOf);
		}

		static KeyColumn uuid(String column) {
			return new KeyColumn(column, column, UUID::fromString, Object::toString);
		}
	}

	/** The pagination SQL tail (predicate + order + limit) and its ordered bind params. */
	record Tail(String sql, List<Object> params) {
	}

	/** A trimmed page of rows and the cursor for the next page ({@code null} when exhausted). */
	record Page(List<Map<String, Object>> rows, String nextCursor) {
	}

	private final KeyColumn sort;
	private final KeyColumn id;
	private final int pageSize;

	private KeysetPage(KeyColumn sort, KeyColumn id, int pageSize) {
		this.sort = sort;
		this.id = id;
		this.pageSize = pageSize;
	}

	/** Ordering is always descending — the only direction these endpoints use. */
	static KeysetPage of(KeyColumn sort, KeyColumn id, int pageSize) {
		return new KeysetPage(sort, id, pageSize);
	}

	/**
	 * The SQL tail to append after the caller's filter {@code WHERE}. Limits to
	 * {@code pageSize + 1} — the extra row is how {@link #paginate} detects a next
	 * page. Bind params are ordered to follow the caller's filter params.
	 */
	Tail build(String cursor) {
		StringBuilder sql = new StringBuilder();
		List<Object> params = new ArrayList<>();
		if (cursor != null && !cursor.isBlank()) {
			String[] parts = decode(cursor);
			sql.append(" AND (").append(sort.column()).append(", ").append(id.column()).append(") < (?, ?)");
			params.add(sort.bind().apply(parts[0]));
			params.add(id.bind().apply(parts[1]));
		}
		sql.append(" ORDER BY ").append(sort.column()).append(" DESC, ").append(id.column()).append(" DESC LIMIT ")
			.append(pageSize + 1);
		return new Tail(sql.toString(), params);
	}

	/** Trims the raw {@code pageSize + 1} rows to a page and encodes the next cursor. */
	Page paginate(List<Map<String, Object>> rows) {
		return paginate(rows, pageSize);
	}

	/**
	 * The same trim against a smaller page than the statement fetched, for a caller
	 * that wants fewer rows than the page size the SQL was built with.
	 *
	 * <p>The statement's {@code LIMIT} is unchanged, so this trims the payload and
	 * not the work — the same trade {@code find_transactions} and
	 * {@code performance_overview} make. It is the trade worth making on the MCP
	 * Surface: the index scan costs the same for a hundred rows as for
	 * twenty-five, while the hundred rows are what a context window is spent on.
	 * Keeping the SQL identical is also what lets {@code McpToolQueryReuseTest}
	 * still assert these Tools run the list page's own statement, byte for byte.
	 *
	 * <p>The cursor is encoded from the last row actually returned, so paging from
	 * a trimmed page resumes where the caller stopped reading rather than where the
	 * statement stopped fetching.
	 */
	Page paginate(List<Map<String, Object>> rows, int size) {
		int trim = Math.max(1, Math.min(size, pageSize));
		boolean hasMore = rows.size() > trim;
		List<Map<String, Object>> pageRows = hasMore ? rows.subList(0, trim) : rows;
		String nextCursor = null;
		if (hasMore && !pageRows.isEmpty()) {
			Map<String, Object> last = pageRows.get(pageRows.size() - 1);
			nextCursor = encode(sort.format().apply(last.get(sort.key())),
					id.format().apply(last.get(id.key())));
		}
		return new Page(pageRows, nextCursor);
	}

	private static String encode(String sortValue, String id) {
		return Base64.getUrlEncoder().withoutPadding()
			.encodeToString((sortValue + "|" + id).getBytes(StandardCharsets.UTF_8));
	}

	/** Decodes a cursor into its {@code [sortValue, id]} parts; throws on a malformed cursor. */
	private static String[] decode(String cursor) {
		String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
		String[] parts = decoded.split("\\|", 2);
		if (parts.length != 2) {
			throw new IllegalArgumentException("invalid cursor");
		}
		return parts;
	}
}
