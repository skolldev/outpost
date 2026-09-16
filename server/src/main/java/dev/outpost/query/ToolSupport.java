package dev.outpost.query;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * What every Tool on the MCP Surface shares: Project slug resolution, the
 * default time window applied when a caller supplies none, and a bounded
 * {@link JdbcTemplate}. The template owns its own statement timeout,
 * deliberately separate from the shared bean the UI's controllers use, so a
 * runaway agent query can't queue behind ingest writes indefinitely.
 */
@Component
class ToolSupport {

	/**
	 * Default lookback applied when {@code from} is omitted. Must match the range
	 * picker's default in {@code ui/src/app/core/filters.ts} — the list indexes
	 * are tuned for that window.
	 */
	static final int DEFAULT_WINDOW_DAYS = 14;

	private final JdbcTemplate jdbc;

	// Own JdbcTemplate, not the shared bean (ADR-0001, ADR-0003) — the timeout must not apply to the UI's queries.
	ToolSupport(DataSource dataSource, @Value("${outpost.mcp.query-timeout-seconds:15}") int queryTimeoutSeconds) {
		this.jdbc = new JdbcTemplate(dataSource);
		this.jdbc.setQueryTimeout(queryTimeoutSeconds);
	}

	JdbcTemplate jdbc() {
		return jdbc;
	}

	/**
	 * The same bounded template as a {@link JdbcClient}, for callers outside this
	 * package (e.g. {@code UptimeStatusService}) written against that API.
	 */
	JdbcClient jdbcClient() {
		return JdbcClient.create(jdbc);
	}

	// --------------------------------------------------------------- projects

	/**
	 * The Projects this installation holds, both ways round. Tools take a slug,
	 * never an id, so an unknown slug is caught here before it reaches a statement
	 * instead of silently matching nothing.
	 */
	record Projects(Map<String, Long> idBySlug, Map<Long, String> slugById, Map<Long, String> nameById) {

		/**
		 * The ids behind {@code slugs}, or an empty list for "every Project" (how
		 * {@link QuerySupport#appendInClause} reads an absent filter). An unknown slug
		 * throws rather than being silently dropped, which would otherwise widen the
		 * result to every Project without saying so.
		 */
		List<Long> resolve(@Nullable List<String> slugs) {
			if (slugs == null || slugs.isEmpty()) {
				return List.of();
			}
			List<Long> ids = new ArrayList<>();
			for (String slug : slugs) {
				Long id = idBySlug.get(slug == null ? null : slug.trim());
				if (id == null) {
					throw new IllegalArgumentException("no Project has the slug '" + slug
							+ "'; call list_projects for the slugs this installation has");
				}
				ids.add(id);
			}
			return ids;
		}

		/** One Project's slug. Null only for a Project deleted between two statements. */
		@Nullable
		String slug(long id) {
			return slugById.get(id);
		}
	}

	/**
	 * Rejects an Environment Name no telemetry has ever arrived under, before it
	 * reaches a statement — an unknown value in an equality predicate would
	 * otherwise match nothing and look like a legitimate empty answer. Checked
	 * installation-wide rather than per filtered Project, since "exists elsewhere
	 * but not here" is a valid empty answer and only a global unknown is a typo.
	 */
	void requireKnownEnvironments(@Nullable List<String> environments) {
		if (environments == null || environments.isEmpty()) {
			return;
		}
		SearchQuery search = ProjectController.buildEnvironmentsQuery(List.of());
		Set<String> known = new HashSet<>();
		jdbc.query(search.sql(), rs -> {
			known.add(rs.getString("name"));
		}, search.params().toArray());
		for (String environment : environments) {
			if (environment == null || !known.contains(environment)) {
				throw new IllegalArgumentException("no telemetry has arrived under an Environment named '" + environment
						+ "'. Environment Names are matched exactly; list_projects shows the names this "
						+ "installation has received.");
			}
		}
	}

	/**
	 * Rejects a release version no telemetry has ever carried, for the same reason
	 * {@link #requireKnownEnvironments} rejects an unknown name.
	 */
	void requireKnownRelease(@Nullable String release) {
		if (release == null || release.isBlank()) {
			return;
		}
		SearchQuery search = ReleaseController.buildKnownReleaseQuery(release);
		Long matches = jdbc.queryForObject(search.sql(), Long.class, search.params().toArray());
		if (matches == null || matches == 0) {
			throw new IllegalArgumentException("no Project has received telemetry for a release '" + release
					+ "'. Release versions are matched exactly, e.g. shop@1.4.2; list_projects shows each "
					+ "Project's most recent versions.");
		}
	}

	/**
	 * A snapshot of the Project catalogue, read through the controller's own
	 * statement (ADR-0016). One extra round trip per Tool call is cheaper than
	 * joining {@code project} into every statement below just to carry a slug.
	 */
	Projects projects() {
		SearchQuery search = ProjectController.buildProjectListQuery();
		Map<String, Long> idBySlug = new LinkedHashMap<>();
		Map<Long, String> slugById = new LinkedHashMap<>();
		Map<Long, String> nameById = new LinkedHashMap<>();
		jdbc.query(search.sql(), rs -> {
			long id = rs.getLong("id");
			String slug = rs.getString("slug");
			idBySlug.put(slug, id);
			slugById.put(id, slug);
			nameById.put(id, rs.getString("name"));
		}, search.params().toArray());
		return new Projects(idBySlug, slugById, nameById);
	}

	// ----------------------------------------------------------------- window

	/**
	 * The window a Tool answered over, echoed in every payload that takes one so
	 * it survives truncation and re-summarization better than a caveat sentence
	 * would.
	 *
	 * @param defaulted whether the caller supplied {@code from}
	 */
	record Window(String from, String to, boolean defaulted) {

		Instant fromInstant() {
			return Instant.parse(from);
		}

		Instant toInstant() {
			return Instant.parse(to);
		}
	}

	/**
	 * Resolves the {@code from}/{@code to} pair a Tool was called with, applying
	 * the default window and disclosing it. {@code from} also accepts an ISO-8601
	 * duration (e.g. {@code PT1H}) meaning that far back from {@code to}.
	 */
	static Window window(@Nullable String from, @Nullable String to, List<String> caveats) {
		Instant upper = blank(to) ? Instant.now() : parse(to, "to");
		boolean defaulted = blank(from);
		Instant lower = defaulted ? upper.minus(Duration.ofDays(DEFAULT_WINDOW_DAYS)) : lower(from, upper);
		if (!lower.isBefore(upper)) {
			throw new IllegalArgumentException("from (" + lower + ") must be before to (" + upper + ")");
		}
		if (defaulted) {
			caveats.add("from was not supplied, so the default window of the last " + DEFAULT_WINDOW_DAYS
					+ " days was applied and nothing older than " + lower + " was read. The window actually used is "
					+ "reported in the window field. Supply from to read further back.");
		}
		return new Window(lower.toString(), upper.toString(), defaulted);
	}

	/**
	 * The start of the window: an instant, or a duration counted back from
	 * {@code upper}, disambiguated by the first character (ISO-8601 durations
	 * start with {@code P}, instants with a digit).
	 */
	private static Instant lower(String from, Instant upper) {
		String value = from.trim();
		if (value.regionMatches(true, 0, "P", 0, 1)) {
			Duration back;
			try {
				back = Duration.parse(value);
			}
			catch (DateTimeParseException e) {
				throw new IllegalArgumentException("from must be an ISO-8601 instant in UTC, such as "
						+ "2026-08-29T14:30:00Z, or an ISO-8601 duration such as PT1H; got '" + from + "'");
			}
			if (back.isZero() || back.isNegative()) {
				throw new IllegalArgumentException("a duration for from must be positive; got '" + from + "'");
			}
			return upper.minus(back);
		}
		return parse(value, "from");
	}

	private static boolean blank(@Nullable String value) {
		return value == null || value.isBlank();
	}

	/**
	 * Resolves a Tool's per-call result limit, applying its default or bounds and
	 * disclosing either change to the caller.
	 */
	static int limit(@Nullable Integer requested, int fallback, int max, String rows, List<String> caveats) {
		if (requested == null) {
			caveats.add("limit was not supplied, so at most " + fallback + " " + rows + " are returned per call. "
					+ "Supply limit to return between 1 and " + max + ".");
			return fallback;
		}
		int applied = Math.max(1, Math.min(requested, max));
		if (applied != requested) {
			caveats.add("limit " + requested + " is outside the accepted range of 1 to " + max
					+ ", so it was clamped to " + applied + ".");
		}
		return applied;
	}

	/**
	 * Text cut to {@code max} characters, or returned as it arrived. The kept part
	 * is the text verbatim — nothing here summarizes.
	 */
	@Nullable
	static String truncate(@Nullable String text, int max) {
		return text != null && text.length() > max ? text.substring(0, max) : text;
	}

	/**
	 * Rejects a malformed instant, naming the parameter and an example value — a
	 * raw {@code DateTimeParseException} only names a string index.
	 */
	private static Instant parse(String value, String parameter) {
		try {
			return Instant.parse(value.trim());
		}
		catch (DateTimeParseException e) {
			throw new IllegalArgumentException(parameter + " must be an ISO-8601 instant in UTC, "
					+ "such as 2026-08-29T14:30:00Z; got '" + value + "'");
		}
	}

}
