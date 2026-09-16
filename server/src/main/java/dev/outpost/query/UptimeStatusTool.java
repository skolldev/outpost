package dev.outpost.query;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.outpost.uptime.UptimeStatusService;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * The MCP Surface's {@code uptime_status} Tool: every Uptime Monitor, its
 * state, any open Incident, and its daily Uptime Check rollup — read through
 * {@link UptimeStatusService}, shared with the status page. Field
 * {@code successful_checks_pct} deliberately differs in name from
 * {@code /api/internal/uptime/overview}'s {@code uptime_pct} (ADR-0014): it
 * measures probe success, not availability.
 */
@Component
public class UptimeStatusTool {

	/**
	 * @param window_days the span read over, ending today — a property of the
	 * query, not of how much history a Monitor actually has (see each Monitor's
	 * {@code days}).
	 */
	public record UptimeStatusResult(int window_days, List<MonitorPayload> monitors, List<String> caveats) {
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

		List<MonitorPayload> monitors = new ArrayList<>();
		for (UptimeStatusService.MonitorOverview monitor : uptime.overview(support.jdbcClient(), projectIds, window)
			.monitors()) {
			List<DayPayload> buckets = monitor.days()
				.stream()
				.map(day -> new DayPayload(day.date().toString(), day.total(), day.failures(), day.uptimePct(),
						day.avgLatencyMs()))
				.toList();
			long received = buckets.stream().mapToLong(DayPayload::checks_received).sum();
			long failed = buckets.stream().mapToLong(DayPayload::checks_failed).sum();
			monitors.add(new MonitorPayload(monitor.id(), monitor.projectSlug(), monitor.environment(),
					monitor.url(), monitor.intervalSeconds(), monitor.status(), incident(monitor), received, failed,
					// Null (not 0 or 100) when nothing was probed — an empty percentage isn't 0% failure.
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
				+ "success, so status lags a change by up to three intervals. An Incident that opened before "
				+ "the window is still reported, because it is still open now.");
		return new UptimeStatusResult(window, monitors, caveats);
	}

	private static OpenIncidentPayload incident(UptimeStatusService.MonitorOverview monitor) {
		UptimeStatusService.OpenIncident incident = monitor.openIncident();
		return incident == null ? null
				: new OpenIncidentPayload(incident.id(), incident.openedAt().toString(), incident.lastError());
	}

	/**
	 * Clamps rather than rejects, disclosing the clamp — a too-wide request returns
	 * a superset, so only a silent narrowing would mislead.
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
