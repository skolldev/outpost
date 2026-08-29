package dev.outpost.query;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * What every Tool on the MCP Surface shares: the bounded {@link JdbcTemplate}
 * they all query through, the Project slug the caller names a Project by, and
 * the time window applied when the caller names none.
 *
 * <p>The window default and the slug translation are here for the same reason —
 * <b>an agent omits what a user never has to pick</b>. ADR-0016 states it as a
 * performance rule: the query factories these Tools reuse arrive with their
 * guards written for the shapes the UI sends, and every real page load carries a
 * time range because the range picker has a default. A Tool that passed an
 * agent's silence straight through would run the unbounded shape nobody
 * measured, which is #126's failure mode on a new surface. So the default is
 * applied here, once, and disclosed by every Tool that applies it.
 *
 * <p>The {@code JdbcTemplate} is here for a different reason, and it is worth
 * being clear that it is a different one: the statement timeout below belongs to
 * the MCP path and to nothing else, so it needs exactly one owner that the
 * controllers do not share.
 *
 * <p>Package-private, and deliberately not a bean any controller can reach: the
 * timeout below belongs to the MCP path and putting it on the shared
 * {@code JdbcTemplate} would put it on the UI's queries too.
 */
@Component
class ToolSupport {

	/**
	 * How far back a Tool reads when the caller supplies no {@code from}. Fourteen
	 * days because that is the range picker's default in
	 * {@code ui/src/app/core/filters.ts}, and therefore the shape the list indexes
	 * were tuned against — matching it is the whole point of having a default at
	 * all.
	 */
	static final int DEFAULT_WINDOW_DAYS = 14;

	private final JdbcTemplate jdbc;

	/**
	 * A statement timeout bounds this path, per the MCP Surface's performance rules.
	 * It is a backstop for the unexpected rather than the plan for the expected —
	 * ADR-0001 and ADR-0003 put these queries on the same single Postgres the ingest
	 * pipeline is writing to, so a runaway agent query is a runaway ingest queue.
	 *
	 * <p>Its own {@code JdbcTemplate} rather than the shared one, because the timeout
	 * is the point: setting it on the injected bean would put it on every controller.
	 */
	ToolSupport(DataSource dataSource, @Value("${outpost.mcp.query-timeout-seconds:15}") int queryTimeoutSeconds) {
		this.jdbc = new JdbcTemplate(dataSource);
		this.jdbc.setQueryTimeout(queryTimeoutSeconds);
	}

	JdbcTemplate jdbc() {
		return jdbc;
	}

	/**
	 * The same bounded template as a {@link JdbcClient}, for the statements outside
	 * this package that are written against that API — {@code UptimeStatusService}'s.
	 * Its callers choose the client so the timeout can be the Tool's without becoming
	 * the UI's.
	 */
	JdbcClient jdbcClient() {
		return JdbcClient.create(jdbc);
	}

	// --------------------------------------------------------------- projects

	/**
	 * The Projects this installation holds, both ways round.
	 *
	 * <p><b>Tools name a Project by its slug, never by its id.</b> The slug is what a
	 * developer types, what the DSN carries and what {@code list_projects} returns
	 * first; an id is an implementation detail an agent can only have learned from a
	 * URL it was pasted. Ids stay out of Tool parameters entirely and the translation
	 * happens here — which also means an unknown slug is caught before it reaches a
	 * statement, rather than silently matching nothing.
	 */
	record Projects(Map<String, Long> idBySlug, Map<Long, String> slugById, Map<Long, String> nameById) {

		/**
		 * The ids behind {@code slugs}, or an empty list for "every Project" — which is
		 * how {@link QuerySupport#appendInClause} reads an absent filter.
		 *
		 * <p>An unknown slug throws rather than being dropped. A dropped filter widens
		 * the answer silently, and the caller would read a result spanning every Project
		 * as one scoped to the Project it asked for — the one failure here that produces
		 * a confidently wrong conclusion rather than an error.
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
	 * A snapshot of the Project catalogue, read through the controller's own
	 * statement (ADR-0016). One extra round trip per Tool call, over a table holding
	 * one row per Project — cheaper than joining {@code project} into every
	 * statement below just to carry a slug through it.
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
	 * The window a Tool answered over, echoed in the payload of every Tool that
	 * takes one.
	 *
	 * <p>Echoed as a field rather than left to a caveat because ADR-0014's argument
	 * about field names applies to values too: a window in the response is attached
	 * to the numbers it produced and survives every truncation those numbers do,
	 * whereas a sentence at the bottom of a caveats array is the first thing a
	 * re-summarization drops.
	 *
	 * @param defaulted whether the caller named the start of it, so a reader can tell
	 * "the last fourteen days because you asked" from "the last fourteen days because
	 * you did not"
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
	 * Resolves the {@code from}/{@code to} pair a Tool was called with, applying the
	 * default and disclosing it.
	 *
	 * <p>ISO-8601 instants rather than a "last N hours" integer, even though a
	 * relative window is what an agent usually means. A model has no reliable clock,
	 * so it cannot convert between the two — but every timestamp in every payload
	 * these Tools return is absolute, which makes an absolute window the one an agent
	 * can narrow to <em>from a previous result</em>. The relative case is covered by
	 * omitting {@code from} entirely.
	 */
	static Window window(@Nullable String from, @Nullable String to, List<String> caveats) {
		Instant upper = blank(to) ? Instant.now() : parse(to, "to");
		boolean defaulted = blank(from);
		Instant lower = defaulted ? upper.minus(Duration.ofDays(DEFAULT_WINDOW_DAYS)) : parse(from, "from");
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

	private static boolean blank(@Nullable String value) {
		return value == null || value.isBlank();
	}

	/**
	 * The value of a whitelisted enumerated parameter, or {@code fallback} when the
	 * caller named none.
	 *
	 * <p><b>An unrecognised value is rejected, never coerced to the fallback.</b> A
	 * caller handed a different sort or status than it asked for reads the result as
	 * the one it asked for, and nothing in the payload contradicts it — which is the
	 * single failure on this surface that produces a confident wrong answer rather
	 * than an error.
	 */
	static String choose(@Nullable String requested, Set<String> allowed, String fallback, String parameter) {
		if (blank(requested)) {
			return fallback;
		}
		String normalized = requested.trim().toLowerCase(Locale.ROOT);
		if (!allowed.contains(normalized)) {
			throw new IllegalArgumentException(
					parameter + " must be one of " + String.join(", ", allowed) + "; got '" + requested + "'");
		}
		return normalized;
	}

	/**
	 * Text cut to {@code max} characters, or returned as it arrived.
	 *
	 * <p>Shared by the Tools that return a Log Record body, which is frequently a
	 * stack trace: a page of them is a context window. The kept part is the received
	 * text verbatim — nothing here summarizes — and the caller is told the cut
	 * happened by the Tool that made it.
	 */
	@Nullable
	static String truncate(@Nullable String text, int max) {
		return text != null && text.length() > max ? text.substring(0, max) : text;
	}

	/**
	 * Rejects a malformed instant by name. The message says which parameter and what
	 * a good value looks like, because the caller cannot read this source and a
	 * {@code DateTimeParseException}'s own text names an index in a string.
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
