package dev.outpost.notifications;

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
			case NotificationOccurrence.Test test -> writeTest(root, test, context);
		}
		return write(root);
	}

	private void writeNewIssue(ObjectNode root, NotificationOccurrence.NewIssue occurrence,
			NotificationContext context) {
		ObjectNode project = root.putObject("project");
		project.put("id", occurrence.projectId());
		project.put("slug", context.projectSlug());
		project.put("name", context.projectName());

		ObjectNode issue = root.putObject("issue");
		issue.put("id", occurrence.issueId());
		issue.put("title", occurrence.title());
		issue.put("culprit", occurrence.culprit());
		issue.put("environment", occurrence.environment());
		issue.put("first_seen", occurrence.firstSeen() == null ? null : occurrence.firstSeen().toString());
		issue.put("link", context.link());
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
