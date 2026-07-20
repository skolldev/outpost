package dev.outpost.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.outpost.query.KeysetPage.KeyColumn;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pure tests for the keyset-pagination seam: the cursor codec (per column type),
 * the {@code n+1} trim boundary, and the build-phase SQL tail. No database — the
 * whole point of keeping {@link KeysetPage} pure is that this is the test
 * surface. The strongest checks round-trip a cursor: {@code paginate} encodes it
 * from the last row, {@code build} decodes it back into the bind params that
 * would re-anchor the next page.
 */
class KeysetPageTest {

	// ------------------------------------------------------------ cursor codec

	@Test
	void instantSortUuidIdCursorRoundTrips() {
		KeysetPage page = KeysetPage.of(KeyColumn.instant("\"timestamp\"", "timestamp"), KeyColumn.uuid("id"), 2);
		Instant lastTs = Instant.parse("2026-07-20T10:15:30Z");
		UUID lastId = UUID.fromString("11111111-2222-3333-4444-555555555555");

		// A page that overflows (3 rows for pageSize 2) so a next cursor is produced.
		KeysetPage.Page result = page.paginate(List.of(row("timestamp", Instant.parse("2026-07-20T10:15:32Z"), UUID.randomUUID()),
				row("timestamp", lastTs, lastId), row("timestamp", Instant.parse("2026-07-20T10:15:28Z"), UUID.randomUUID())));

		assertThat(result.rows()).hasSize(2);
		assertThat(result.nextCursor()).isNotNull();

		// Feeding the cursor back must decode to the last retained row's (ts, id),
		// bound as the SQL layer expects (Timestamp, UUID).
		KeysetPage.Tail tail = page.build(result.nextCursor());
		assertThat(tail.params()).containsExactly(Timestamp.from(lastTs), lastId);
	}

	@Test
	void longSortLongIdCursorRoundTrips() {
		// The issues sort=count path: (event_count, id), both bigint.
		KeysetPage page = KeysetPage.of(KeyColumn.longs("event_count"), KeyColumn.longs("id"), 2);

		KeysetPage.Page result = page.paginate(List.of(row("event_count", 900L, 5L), row("event_count", 42L, 7L),
				row("event_count", 10L, 9L)));

		assertThat(result.nextCursor()).isNotNull();
		KeysetPage.Tail tail = page.build(result.nextCursor());
		assertThat(tail.params()).containsExactly(42L, 7L);
	}

	@Test
	void instantSortLongIdCursorRoundTrips() {
		// The issues default path: (last_seen, id) — timestamp sort, bigint id.
		KeysetPage page = KeysetPage.of(KeyColumn.instant("last_seen"), KeyColumn.longs("id"), 2);
		Instant lastSeen = Instant.parse("2026-07-19T00:00:00Z");

		KeysetPage.Page result = page.paginate(List.of(row("last_seen", Instant.parse("2026-07-19T02:00:00Z"), 1L),
				row("last_seen", lastSeen, 2L), row("last_seen", Instant.parse("2026-07-18T00:00:00Z"), 3L)));

		KeysetPage.Tail tail = page.build(result.nextCursor());
		assertThat(tail.params()).containsExactly(Timestamp.from(lastSeen), 2L);
	}

	@Test
	void malformedCursorMissingSeparatorThrows() {
		KeysetPage page = KeysetPage.of(KeyColumn.longs("event_count"), KeyColumn.longs("id"), 2);
		String noPipe = Base64.getUrlEncoder().withoutPadding().encodeToString("nopipe".getBytes());
		assertThatThrownBy(() -> page.build(noPipe)).isInstanceOf(IllegalArgumentException.class)
			.hasMessage("invalid cursor");
	}

	@Test
	void malformedCursorNotBase64Throws() {
		KeysetPage page = KeysetPage.of(KeyColumn.longs("event_count"), KeyColumn.longs("id"), 2);
		assertThatThrownBy(() -> page.build("!!! not base64 !!!")).isInstanceOf(IllegalArgumentException.class);
	}

	// -------------------------------------------------------------- trim boundary

	@Test
	void overflowTrimsToPageSizeAndSetsCursor() {
		KeysetPage page = KeysetPage.of(KeyColumn.longs("event_count"), KeyColumn.longs("id"), 2);
		KeysetPage.Page result = page.paginate(rows(3));
		assertThat(result.rows()).hasSize(2);
		assertThat(result.nextCursor()).isNotNull();
	}

	@Test
	void exactlyPageSizeKeepsAllAndHasNoCursor() {
		KeysetPage page = KeysetPage.of(KeyColumn.longs("event_count"), KeyColumn.longs("id"), 2);
		KeysetPage.Page result = page.paginate(rows(2));
		assertThat(result.rows()).hasSize(2);
		assertThat(result.nextCursor()).isNull();
	}

	@Test
	void underPageSizeHasNoCursor() {
		KeysetPage page = KeysetPage.of(KeyColumn.longs("event_count"), KeyColumn.longs("id"), 2);
		KeysetPage.Page result = page.paginate(rows(1));
		assertThat(result.rows()).hasSize(1);
		assertThat(result.nextCursor()).isNull();
	}

	@Test
	void emptyRowsHaveNoCursor() {
		KeysetPage page = KeysetPage.of(KeyColumn.longs("event_count"), KeyColumn.longs("id"), 2);
		KeysetPage.Page result = page.paginate(List.of());
		assertThat(result.rows()).isEmpty();
		assertThat(result.nextCursor()).isNull();
	}

	// ------------------------------------------------------------- build phase

	@Test
	void buildWithoutCursorOmitsPredicateAndBindsNothing() {
		KeysetPage page = KeysetPage.of(KeyColumn.instant("\"timestamp\"", "timestamp"), KeyColumn.uuid("id"), 100);
		KeysetPage.Tail tail = page.build(null);
		assertThat(tail.sql()).doesNotContain(" AND (");
		assertThat(tail.sql()).contains("ORDER BY \"timestamp\" DESC, id DESC LIMIT 101");
		assertThat(tail.params()).isEmpty();
	}

	@Test
	void buildWithCursorEmitsRowComparisonPredicate() {
		KeysetPage page = KeysetPage.of(KeyColumn.instant("\"timestamp\"", "timestamp"), KeyColumn.uuid("id"), 100);
		String cursor = KeysetPage.of(KeyColumn.instant("\"timestamp\"", "timestamp"), KeyColumn.uuid("id"), 1)
			.paginate(List.of(row("timestamp", Instant.parse("2026-07-20T10:15:30Z"), UUID.randomUUID()),
					row("timestamp", Instant.parse("2026-07-20T10:15:29Z"), UUID.randomUUID())))
			.nextCursor();
		KeysetPage.Tail tail = page.build(cursor);
		assertThat(tail.sql()).contains(" AND (\"timestamp\", id) < (?, ?)");
		assertThat(tail.params()).hasSize(2);
	}

	// ------------------------------------------------------------------- helpers

	private static Map<String, Object> row(String sortKey, Object sortValue, Object id) {
		Map<String, Object> row = new LinkedHashMap<>();
		row.put(sortKey, sortValue);
		row.put("id", id);
		return row;
	}

	/** {@code n} rows sorted by a descending event_count, ids ascending — shape irrelevant to trimming. */
	private static List<Map<String, Object>> rows(int n) {
		List<Map<String, Object>> rows = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			rows.add(row("event_count", (long) (1000 - i), (long) i));
		}
		return rows;
	}
}
