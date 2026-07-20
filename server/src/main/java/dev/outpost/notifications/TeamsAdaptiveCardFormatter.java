package dev.outpost.notifications;

import java.time.Instant;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The {@code teams} implementation of the {@link NotificationFormatter} seam
 * (issue #46): renders an occurrence as a Microsoft Teams Adaptive Card wrapped
 * in the envelope a Teams Workflows incoming webhook requires
 * ({@code {"type":"message","attachments":[{"contentType":
 * "application/vnd.microsoft.card.adaptive","content":<card>}]}}). Posting a bare
 * card, or the legacy MessageCard {@code {"text":...}}, gets a 400 from a
 * Workflows webhook.
 *
 * <p>Each card presents the same summary facts as the Generic JSON payload for
 * that trigger — a bold title, a {@code FactSet} of key facts, and one
 * {@code Action.OpenUrl} button deep-linking into Outpost — so a reader judges
 * severity at a glance and is one click from diagnosis (parent #41, stories
 * 10–13). Card layout is presentation, not a versioned contract (unlike Generic
 * JSON): this shape may change freely without a version bump.
 */
@Component
public class TeamsAdaptiveCardFormatter implements NotificationFormatter {

	/** Adaptive Card schema version Teams Workflows renders; 1.4 is broadly supported. */
	private static final String CARD_VERSION = "1.4";
	private static final String CARD_SCHEMA = "http://adaptivecards.io/schemas/adaptive-card.json";

	private final ObjectMapper mapper;

	public TeamsAdaptiveCardFormatter(ObjectMapper mapper) {
		this.mapper = mapper;
	}

	@Override
	public String channelType() {
		return "teams";
	}

	@Override
	public String format(NotificationOccurrence occurrence, NotificationContext context) {
		ObjectNode card = mapper.createObjectNode();
		card.put("type", "AdaptiveCard");
		card.put("$schema", CARD_SCHEMA);
		card.put("version", CARD_VERSION);
		ArrayNode body = card.putArray("body");

		switch (occurrence) {
			case NotificationOccurrence.NewIssue issue -> buildNewIssue(body, issue, context);
			case NotificationOccurrence.IncidentStarted incident -> buildIncidentStarted(body, incident, context);
			case NotificationOccurrence.IncidentResolved incident -> buildIncidentResolved(body, incident, context);
			case NotificationOccurrence.Test test -> buildTest(body, test);
		}

		card.set("actions", openUrlAction(linkLabel(occurrence), context.link()));
		return write(envelope(card));
	}

	private void buildNewIssue(ArrayNode body, NotificationOccurrence.NewIssue occurrence, NotificationContext context) {
		title(body, "New issue");
		heading(body, occurrence.title());
		ArrayNode facts = factSet(body);
		fact(facts, "Project", context.projectName());
		fact(facts, "Culprit", occurrence.culprit());
		fact(facts, "Environment", occurrence.environment());
		fact(facts, "First seen", instant(occurrence.firstSeen()));
	}

	private void buildIncidentStarted(ArrayNode body, NotificationOccurrence.IncidentStarted occurrence,
			NotificationContext context) {
		title(body, "Incident started");
		heading(body, occurrence.monitorUrl());
		ArrayNode facts = factSet(body);
		fact(facts, "Project", context.projectName());
		fact(facts, "Monitor", occurrence.monitorUrl());
		fact(facts, "Environment", occurrence.environment());
		fact(facts, "Failure", occurrence.failureReason());
		fact(facts, "Opened", instant(occurrence.openedAt()));
	}

	private void buildIncidentResolved(ArrayNode body, NotificationOccurrence.IncidentResolved occurrence,
			NotificationContext context) {
		title(body, "Incident resolved");
		heading(body, occurrence.monitorUrl());
		ArrayNode facts = factSet(body);
		fact(facts, "Project", context.projectName());
		fact(facts, "Monitor", occurrence.monitorUrl());
		fact(facts, "Environment", occurrence.environment());
		fact(facts, "Downtime",
				occurrence.downtime() == null ? null : NotificationText.humanDuration(occurrence.downtime()));
		fact(facts, "Resolved", instant(occurrence.closedAt()));
	}

	private void buildTest(ArrayNode body, NotificationOccurrence.Test occurrence) {
		title(body, "Test notification");
		heading(body, occurrence.message());
	}

	/** The deep-link button label, per trigger — always a one-click path to diagnosis. */
	private String linkLabel(NotificationOccurrence occurrence) {
		return switch (occurrence) {
			case NotificationOccurrence.NewIssue ignored -> "View issue in Outpost";
			case NotificationOccurrence.IncidentStarted ignored -> "View uptime in Outpost";
			case NotificationOccurrence.IncidentResolved ignored -> "View uptime in Outpost";
			case NotificationOccurrence.Test ignored -> "Open Outpost settings";
		};
	}

	/** Small bold caption naming the trigger, above the heading. */
	private void title(ArrayNode body, String text) {
		ObjectNode block = body.addObject();
		block.put("type", "TextBlock");
		block.put("text", text);
		block.put("weight", "Bolder");
		block.put("size", "Small");
		block.put("isSubtle", true);
	}

	/** The prominent line: the issue title or the monitor URL. */
	private void heading(ArrayNode body, String text) {
		ObjectNode block = body.addObject();
		block.put("type", "TextBlock");
		block.put("text", text == null ? "" : text);
		block.put("weight", "Bolder");
		block.put("size", "Large");
		block.put("wrap", true);
	}

	private ArrayNode factSet(ArrayNode body) {
		ObjectNode set = body.addObject();
		set.put("type", "FactSet");
		return set.putArray("facts");
	}

	/** Adds a fact only when it has a value — a null fact is omitted, not shown as "null". */
	private void fact(ArrayNode facts, String title, String value) {
		if (value == null || value.isBlank()) {
			return;
		}
		ObjectNode fact = facts.addObject();
		fact.put("title", title);
		fact.put("value", value);
	}

	private ArrayNode openUrlAction(String label, String url) {
		ArrayNode actions = mapper.createArrayNode();
		ObjectNode action = actions.addObject();
		action.put("type", "Action.OpenUrl");
		action.put("title", label);
		action.put("url", url);
		return actions;
	}

	/** Wraps the card in the Teams Workflows message/attachments envelope. */
	private ObjectNode envelope(ObjectNode card) {
		ObjectNode root = mapper.createObjectNode();
		root.put("type", "message");
		ArrayNode attachments = root.putArray("attachments");
		ObjectNode attachment = attachments.addObject();
		attachment.put("contentType", "application/vnd.microsoft.card.adaptive");
		attachment.set("content", card);
		return root;
	}

	private static String instant(Instant instant) {
		return instant == null ? null : instant.toString();
	}

	private String write(ObjectNode root) {
		return NotificationText.serialize(mapper, root);
	}
}
