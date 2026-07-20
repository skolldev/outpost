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
 * Keyset pagination for the query list endpoints. A <b>keyset page</b> is a slice
 * of query results ordered by {@code (sort value, id)} descending and navigated
 * by an opaque <b>cursor</b> — the base64url token {@code sortValue|id} of the
 * last row on the previous page. Keyset (not offset) pagination keeps every page
 * O(page size) regardless of depth, using the {@code (sort, id) < (?, ?)} row
 * comparison against an index on the same columns.
 *
 * <p>The seam is pure and has two phases, wrapped around the caller's own
 * {@code jdbc.query}:
 * <ul>
 * <li>{@link #build(String)} — turns a cursor into the SQL tail
 * ({@code AND (sort, id) < (?, ?)}, {@code ORDER BY sort DESC, id DESC},
 * {@code LIMIT pageSize + 1}) plus its bind params. The caller appends this to
 * its filter SQL, so the readable per-endpoint {@code WHERE} stays in the
 * controller and the exact SQL remains {@code EXPLAIN}-able without a database.
 * <li>{@link #paginate(List)} — trims the {@code pageSize + 1} raw rows to a page
 * and encodes the next cursor. Enrichment (e.g. sparklines) is layered on the
 * returned rows by the caller, so it never enters this interface.
 * </ul>
 *
 * <p>Each {@link KeyColumn} owns its cursor codec — how to {@link
 * KeyColumn#format format} a row value into the cursor string and {@link
 * KeyColumn#bind bind} a cursor string back into a SQL parameter — so the typed
 * parse logic that used to be copied across controllers lives in exactly one
 * place. Ordering is always descending, the only direction these endpoints use.
 */
final class KeysetPage {

	/**
	 * One column of a keyset, paired with the codec that moves its value across
	 * the cursor boundary: {@link #bind} parses a cursor string part into a SQL
	 * bind parameter, {@link #format} renders a row value into a cursor string.
	 *
	 * <p>{@code column} is the SQL identifier used in the predicate and
	 * {@code ORDER BY} (may be quoted, e.g. {@code "timestamp"}); {@code key} is
	 * the result-row map key {@link #paginate} reads to build the next cursor.
	 * They differ only when the SQL name needs quoting; the factories default
	 * {@code key} to {@code column} and take an explicit {@code key} overload for
	 * that case.
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

		/** A {@code bigint} column (e.g. an identity id or an event count). */
		static KeyColumn longs(String column) {
			return new KeyColumn(column, column, Long::parseLong, String::valueOf);
		}

		/** A {@code uuid} column. */
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

	/** A keyset ordered by {@code sort} then {@code id}, both descending, paging {@code pageSize} rows. */
	static KeysetPage of(KeyColumn sort, KeyColumn id, int pageSize) {
		return new KeysetPage(sort, id, pageSize);
	}

	/**
	 * The SQL tail to append after the caller's filter {@code WHERE}: the cursor
	 * row-comparison (when a cursor is given), the {@code ORDER BY}, and
	 * {@code LIMIT pageSize + 1} — one extra row so {@link #paginate} can tell
	 * whether a next page exists. Bind params are ordered to follow the caller's
	 * filter params.
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

	/**
	 * Trims the raw {@code pageSize + 1} rows to a page and encodes the next
	 * cursor from the last retained row. When {@code rows} does not overflow the
	 * page, the next cursor is {@code null}.
	 */
	Page paginate(List<Map<String, Object>> rows) {
		boolean hasMore = rows.size() > pageSize;
		List<Map<String, Object>> pageRows = hasMore ? rows.subList(0, pageSize) : rows;
		String nextCursor = null;
		if (hasMore && !pageRows.isEmpty()) {
			Map<String, Object> last = pageRows.get(pageRows.size() - 1);
			nextCursor = encode(sort.format().apply(last.get(sort.key())),
					id.format().apply(last.get(id.key())));
		}
		return new Page(pageRows, nextCursor);
	}

	/** Encodes a keyset cursor as base64url of {@code sortValue|id}. */
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
