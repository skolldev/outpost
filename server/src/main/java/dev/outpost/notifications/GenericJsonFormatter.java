package dev.outpost.notifications;

import java.time.Duration;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Formats the Generic JSON payload — a public, versioned contract for custom
 * receivers (parent #41, user story 14). Keys are written explicitly in
 * snake_case rather than derived from a naming strategy so the wire shape is a
 * deliberate contract, not a serialization side effect, and stays stable
 * regardless of the app's Jackson configuration.
 *
 * <p>Contract rules: top-level {@code version} is {@code 1}; {@code type} is the
 * trigger discriminator; changes within a version are additive only. Documented
 * in {@code docs/notifications/generic-json-payload.md}.
 */
@Component
public class GenericJsonFormatter {

	private static final int VERSION = 1;

	private final ObjectMapper mapper;

	public GenericJsonFormatter(ObjectMapper mapper) {
		this.mapper = mapper;
	}

	/** Serializes {@code occurrence} into the Generic JSON payload for one delivery. */
	public String format(NotificationOccurrence occurrence, NotificationContext context) {
		ObjectNode root = mapper.createObjectNode();
		root.put("version", VERSION);
		root.put("type", occurrence.triggerType());
		switch (occurrence) {
			case NotificationOccurrence.NewIssue issue -> writeNewIssue(root, issue, context);
			case NotificationOccurrence.IncidentStarted incident -> writeIncidentStarted(root, incident, context);
			case NotificationOccurrence.IncidentResolved incident -> writeIncidentResolved(root, incident, context);
			case NotificationOccurrence.Test test -> writeTest(root, test, context);
		}
		return write(root);
	}

	private void writeNewIssue(ObjectNode root, NotificationOccurrence.NewIssue occurrence,
			NotificationContext context) {
		writeProject(root, occurrence.projectId(), context);

		ObjectNode issue = root.putObject("issue");
		issue.put("id", occurrence.issueId());
		issue.put("title", occurrence.title());
		issue.put("culprit", occurrence.culprit());
		issue.put("environment", occurrence.environment());
		issue.put("first_seen", occurrence.firstSeen() == null ? null : occurrence.firstSeen().toString());
		issue.put("link", context.link());
	}

	private void writeIncidentStarted(ObjectNode root, NotificationOccurrence.IncidentStarted occurrence,
			NotificationContext context) {
		writeProject(root, occurrence.projectId(), context);
		ObjectNode incident = writeMonitor(root, occurrence.monitorId(), occurrence.monitorUrl(),
				occurrence.environment(), context);
		incident.put("failure_reason", occurrence.failureReason());
		incident.put("opened_at", occurrence.openedAt() == null ? null : occurrence.openedAt().toString());
	}

	private void writeIncidentResolved(ObjectNode root, NotificationOccurrence.IncidentResolved occurrence,
			NotificationContext context) {
		writeProject(root, occurrence.projectId(), context);
		ObjectNode incident = writeMonitor(root, occurrence.monitorId(), occurrence.monitorUrl(),
				occurrence.environment(), context);
		incident.put("opened_at", occurrence.openedAt() == null ? null : occurrence.openedAt().toString());
		incident.put("closed_at", occurrence.closedAt() == null ? null : occurrence.closedAt().toString());
		incident.put("downtime_seconds", occurrence.downtime() == null ? null : occurrence.downtime().toSeconds());
		incident.put("downtime_human", occurrence.downtime() == null ? null : humanDuration(occurrence.downtime()));
	}

	/** Project block, shared by every occurrence type that names a Project. */
	private void writeProject(ObjectNode root, long projectId, NotificationContext context) {
		ObjectNode project = root.putObject("project");
		project.put("id", projectId);
		project.put("slug", context.projectSlug());
		project.put("name", context.projectName());
	}

	/**
	 * The {@code monitor} + {@code incident} blocks common to both incident
	 * triggers. Returns the {@code incident} node so each caller can add its own
	 * trigger-specific fields (failure reason vs. downtime).
	 */
	private ObjectNode writeMonitor(ObjectNode root, long monitorId, String monitorUrl, String environment,
			NotificationContext context) {
		ObjectNode monitor = root.putObject("monitor");
		monitor.put("id", monitorId);
		monitor.put("url", monitorUrl);
		monitor.put("environment", environment);
		monitor.put("link", context.link());

		return root.putObject("incident");
	}

	/**
	 * Compact human-readable duration for the payload's {@code downtime_human}
	 * field, e.g. {@code "2h 5m 30s"}. Presentation sugar alongside the canonical
	 * {@code downtime_seconds}; receivers that compute their own can ignore it.
	 */
	private static String humanDuration(Duration downtime) {
		long seconds = Math.max(0, downtime.toSeconds());
		long hours = seconds / 3600;
		long minutes = (seconds % 3600) / 60;
		long secs = seconds % 60;
		StringBuilder out = new StringBuilder();
		if (hours > 0) {
			out.append(hours).append("h ");
		}
		if (hours > 0 || minutes > 0) {
			out.append(minutes).append("m ");
		}
		out.append(secs).append('s');
		return out.toString();
	}

	private void writeTest(ObjectNode root, NotificationOccurrence.Test occurrence, NotificationContext context) {
		ObjectNode channel = root.putObject("channel");
		channel.put("id", occurrence.channelId());
		channel.put("name", occurrence.channelName());

		root.put("message", occurrence.message());
		root.put("fired_at", occurrence.firedAt() == null ? null : occurrence.firedAt().toString());
		root.put("link", context.link());
	}

	private String write(ObjectNode root) {
		try {
			return mapper.writeValueAsString(root);
		}
		catch (JacksonException e) {
			throw new IllegalStateException("notification payload not serializable", e);
		}
	}
}
