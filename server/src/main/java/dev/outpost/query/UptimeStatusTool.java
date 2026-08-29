package dev.outpost.query;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.outpost.uptime.UptimeStatusService;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * The MCP Surface's {@code uptime_status} Tool: every Uptime Monitor, its
 * current state, the Incident open against it if there is one, and the daily
 * Uptime Check rollup behind that state.
 *
 * <p>It reads through {@link UptimeStatusService}, the same component the status
 * page reads through, so there is no second copy of these statements and no
 * second definition of what {@code up} means. Nothing new is computed here: the
 * window totals below are the sums of the daily buckets the service already
 * produced.
 *
 * <p><b>The percentage is named for what it measures.</b> It is
 * {@code successful_checks_pct}, not {@code uptime_pct}, because the two are not
 * the same claim and only one of them is supportable. Outpost probes on an
 * interval and stores what came back; a minute in which no probe ran leaves no
 * evidence either way, and an outage of Outpost itself records no failed Uptime
 * Checks at all. A field called {@code uptime_pct} invites a model to report
 * availability, which is a stronger statement than a ratio of two counts of
 * probes — exactly the naming failure ADR-0014 forbids.
 */
@Component
public class UptimeStatusTool {

	public record UptimeStatusResult(int days_returned, int days_read, List<MonitorPayload> monitors,
			List<String> caveats) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record MonitorPayload(long id, String project_slug, String environment, String url, int interval_seconds,
			String status, @Nullable OpenIncidentPayload open_incident, long checks_received, long checks_failed,
			@Nullable Double successful_checks_pct, List<DayPayload> days) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record OpenIncidentPayload(long id, String opened_at, @Nullable String last_error) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record DayPayload(String date, long checks_received, long checks_failed, double successful_checks_pct,
			@Nullable Integer avg_latency_ms) {
	}

	/**
	 * Days of history returned when the caller names none. A week answers "is it
	 * flapping" without spending a context window on the ninety days the status page
	 * draws, which is a screen of pixels rather than a screen of text.
	 */
	static final int DEFAULT_DAYS = 7;

	private final UptimeStatusService uptime;

	private final ToolSupport support;

	public UptimeStatusTool(UptimeStatusService uptime, ToolSupport support) {
		this.uptime = uptime;
		this.support = support;
	}

	@McpTool(name = "uptime_status", title = "Uptime status", generateOutputSchema = true,
			annotations = @McpTool.McpAnnotations(title = "Uptime status", readOnlyHint = true,
					destructiveHint = false, idempotentHint = true, openWorldHint = false),
			description = """
					Every Uptime Monitor with its current state (up, down or unknown), the Incident open against it \
					if any, and a per-day rollup of the Uptime Checks behind that. status is `down` while an \
					Incident is open — three consecutive failed Checks open one — and `unknown` when no Check has \
					been recorded yet. Read the `caveats` array: the percentages count probes, not availability.""")
	public UptimeStatusResult uptimeStatus(
			@McpToolParam(required = false,
					description = "Project slugs from list_projects. Omit for every Project.") List<String> project_slugs,
			@McpToolParam(required = false, description = "Days of daily history to return, most recent last. "
					+ "Defaults to " + DEFAULT_DAYS + ", clamped to " + UptimeStatusService.WINDOW_DAYS
					+ ".") Integer days) {

		List<String> caveats = new ArrayList<>();
		List<Long> projectIds = support.projects().resolve(project_slugs);
		int window = days(days, caveats);
		LocalDate earliest = LocalDate.now(ZoneOffset.UTC).minusDays(window - 1L);

		List<MonitorPayload> monitors = new ArrayList<>();
		for (UptimeStatusService.MonitorOverview monitor : uptime.overview().monitors()) {
			if (!projectIds.isEmpty() && !projectIds.contains(monitor.projectId())) {
				continue;
			}
			List<DayPayload> buckets = monitor.days()
				.stream()
				.filter(day -> !day.date().isBefore(earliest))
				.map(day -> new DayPayload(day.date().toString(), day.total(), day.failures(), day.uptimePct(),
						day.avgLatencyMs()))
				.toList();
			long received = buckets.stream().mapToLong(DayPayload::checks_received).sum();
			long failed = buckets.stream().mapToLong(DayPayload::checks_failed).sum();
			monitors.add(new MonitorPayload(monitor.id(), monitor.projectSlug(), monitor.environment(),
					monitor.url(), monitor.intervalSeconds(), monitor.status(), incident(monitor), received, failed,
					// Null rather than 100 when nothing was probed: a percentage of no
					// observations is not a percentage, and zero would read as total failure.
					received == 0 ? null : Math.round((received - failed) * 10_000.0 / received) / 100.0, buckets));
		}

		if (monitors.isEmpty()) {
			caveats.add("No Uptime Monitor matched. Monitors are created in the Outpost UI under Uptime; an "
					+ "installation with none is not evidence that anything is up.");
		}
		caveats.add("successful_checks_pct is the share of Uptime Checks that succeeded, not availability. "
				+ "Outpost probes on each Monitor's interval, so time between probes is unobserved, and an "
				+ "interval in which Outpost itself was not running records no Checks at all.");
		caveats.add("An open Incident is opened by three consecutive failed Uptime Checks and closed by the first "
				+ "success, so status lags a change by up to three intervals.");
		return new UptimeStatusResult(window, UptimeStatusService.WINDOW_DAYS, monitors, caveats);
	}

	private static OpenIncidentPayload incident(UptimeStatusService.MonitorOverview monitor) {
		UptimeStatusService.OpenIncident incident = monitor.openIncident();
		return incident == null ? null
				: new OpenIncidentPayload(incident.id(), incident.openedAt().toString(), incident.lastError());
	}

	/**
	 * The number of days of history to render. Clamped rather than rejected, and the
	 * clamp disclosed: unlike a filter, a too-wide history returns a superset of what
	 * was asked for, so narrowing it silently is the only thing that could mislead.
	 */
	private static int days(Integer requested, List<String> caveats) {
		if (requested == null) {
			caveats.add("days was not supplied, so the default of " + DEFAULT_DAYS + " days of history was applied.");
			return DEFAULT_DAYS;
		}
		if (requested < 1) {
			caveats.add("days was " + requested + ", which is below the 1-day minimum; 1 day was used.");
			return 1;
		}
		if (requested > UptimeStatusService.WINDOW_DAYS) {
			caveats.add("days was clamped from " + requested + " to the " + UptimeStatusService.WINDOW_DAYS
					+ "-day maximum, which is as far back as the rollup goes.");
			return UptimeStatusService.WINDOW_DAYS;
		}
		return requested;
	}

}
