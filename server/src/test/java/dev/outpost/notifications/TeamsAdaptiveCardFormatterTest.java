package dev.outpost.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Pure shape tests for the Teams Adaptive Card payload (issue #46). Assert the
 * Teams Workflows envelope ({@code type: message} → attachments → an Adaptive
 * Card), that each trigger type presents the same summary facts as the Generic
 * JSON payload, and that every card carries a clickable deep link into Outpost.
 *
 * <p>Card layout is presentation, not a versioned contract, so these assert the
 * facts and the envelope Teams requires — not an exact body ordering. Prior art:
 * {@link GenericJsonFormatterTest}.
 */
class TeamsAdaptiveCardFormatterTest {

	private final ObjectMapper mapper = new ObjectMapper();

	private final TeamsAdaptiveCardFormatter formatter = new TeamsAdaptiveCardFormatter(new ObjectMapper());

	@Test
	void channelTypeIsTeams() {
		assertThat(formatter.channelType()).isEqualTo("teams");
	}

	@Test
	void envelopeIsTheTeamsWorkflowsAdaptiveCardShape() {
		JsonNode root = format(new NotificationOccurrence.NewIssue(42, 100, "boom", "main.js", "production",
				Instant.parse("2026-07-20T10:00:00Z")), new NotificationContext("Shop", "shop", "https://o/issues/100"));

		assertThat(root.get("type").asString()).isEqualTo("message");
		JsonNode attachment = root.get("attachments").get(0);
		assertThat(attachment.get("contentType").asString()).isEqualTo("application/vnd.microsoft.card.adaptive");
		JsonNode card = attachment.get("content");
		assertThat(card.get("type").asString()).isEqualTo("AdaptiveCard");
		assertThat(card.get("$schema").asString()).contains("adaptivecards.io");
		assertThat(card.get("version").asString()).isNotEmpty();
		assertThat(card.get("body").isArray()).isTrue();
	}

	@Test
	void newIssueCardShowsSummaryFactsAndTitle() {
		JsonNode card = card(format(
				new NotificationOccurrence.NewIssue(42, 100, "TypeError: undefined is not a function",
						"main.js in handleClick", "production", Instant.parse("2026-07-20T10:00:00Z")),
				new NotificationContext("Shop", "shop", "https://outpost.example/issues/100")));

		assertThat(cardText(card)).contains("TypeError: undefined is not a function");
		Map<String, String> facts = facts(card);
		assertThat(facts).containsEntry("Project", "Shop");
		assertThat(facts).containsEntry("Environment", "production");
		assertThat(facts).containsEntry("Culprit", "main.js in handleClick");
		assertThat(facts.get("First seen")).contains("2026-07-20");
	}

	@Test
	void newIssueCardLinksIntoOutpost() {
		JsonNode card = card(format(
				new NotificationOccurrence.NewIssue(42, 100, "boom", "x", "prod",
						Instant.parse("2026-07-20T10:00:00Z")),
				new NotificationContext("Shop", "shop", "https://outpost.example/issues/100")));

		assertThat(openUrl(card)).isEqualTo("https://outpost.example/issues/100");
	}

	@Test
	void newIssueCardToleratesMissingCulpritAndEnvironment() {
		JsonNode card = card(format(
				new NotificationOccurrence.NewIssue(1, 2, "boom", null, null, Instant.parse("2026-07-20T10:00:00Z")),
				new NotificationContext("P", "p", "https://o/issues/2")));

		// A missing fact is simply omitted from the card rather than shown as "null".
		assertThat(facts(card)).doesNotContainKey("Culprit").doesNotContainKey("Environment");
		assertThat(facts(card)).containsEntry("Project", "P");
	}

	@Test
	void incidentStartedCardShowsMonitorFactsAndFailureReason() {
		JsonNode card = card(format(
				new NotificationOccurrence.IncidentStarted(42, 9, "https://shop.example/health", "production", "HTTP 503",
						Instant.parse("2026-07-20T10:00:00Z")),
				new NotificationContext("Shop", "shop", "https://outpost.example/uptime")));

		Map<String, String> facts = facts(card);
		assertThat(facts).containsEntry("Project", "Shop");
		assertThat(facts).containsEntry("Monitor", "https://shop.example/health");
		assertThat(facts).containsEntry("Environment", "production");
		assertThat(facts).containsEntry("Failure", "HTTP 503");
		assertThat(openUrl(card)).isEqualTo("https://outpost.example/uptime");
	}

	@Test
	void incidentResolvedCardShowsDowntime() {
		JsonNode card = card(format(
				new NotificationOccurrence.IncidentResolved(42, 9, "https://shop.example/health", "production",
						Instant.parse("2026-07-20T10:00:00Z"), Instant.parse("2026-07-20T12:05:30Z"),
						Duration.ofSeconds(7530)),
				new NotificationContext("Shop", "shop", "https://outpost.example/uptime")));

		Map<String, String> facts = facts(card);
		assertThat(facts).containsEntry("Monitor", "https://shop.example/health");
		assertThat(facts.get("Downtime")).isEqualTo("2h 5m 30s");
		assertThat(openUrl(card)).isEqualTo("https://outpost.example/uptime");
	}

	@Test
	void testCardShowsChannelMessageAndSettingsLink() {
		JsonNode card = card(format(new NotificationOccurrence.Test(7, "Ops Teams", Instant.parse("2026-07-20T10:00:00Z")),
				new NotificationContext(null, null, "https://outpost.example/settings")));

		assertThat(cardText(card)).contains("Ops Teams");
		assertThat(openUrl(card)).isEqualTo("https://outpost.example/settings");
	}

	// ------------------------------------------------------------------ helpers

	private JsonNode format(NotificationOccurrence occurrence, NotificationContext context) {
		return mapper.readTree(formatter.format(occurrence, context));
	}

	/** The Adaptive Card body ({@code attachments[0].content}). */
	private JsonNode card(JsonNode root) {
		return root.get("attachments").get(0).get("content");
	}

	/** Concatenated text of every TextBlock in the card body. */
	private String cardText(JsonNode card) {
		StringBuilder out = new StringBuilder();
		for (JsonNode element : card.get("body")) {
			if ("TextBlock".equals(text(element.get("type")))) {
				out.append(text(element.get("text"))).append('\n');
			}
		}
		return out.toString();
	}

	/** Every FactSet fact in the card body, flattened to title → value. */
	private Map<String, String> facts(JsonNode card) {
		Map<String, String> facts = new HashMap<>();
		for (JsonNode element : card.get("body")) {
			if ("FactSet".equals(text(element.get("type")))) {
				for (JsonNode fact : element.get("facts")) {
					facts.put(text(fact.get("title")), text(fact.get("value")));
				}
			}
		}
		return facts;
	}

	/** The first {@code Action.OpenUrl} target in the card. */
	private String openUrl(JsonNode card) {
		for (JsonNode action : card.get("actions")) {
			if ("Action.OpenUrl".equals(text(action.get("type")))) {
				return text(action.get("url"));
			}
		}
		throw new AssertionError("no Action.OpenUrl in card: " + card);
	}

	private String text(JsonNode node) {
		return node == null || node.isNull() ? null : node.asString();
	}
}
