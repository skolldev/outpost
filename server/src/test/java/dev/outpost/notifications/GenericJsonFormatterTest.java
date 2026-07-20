package dev.outpost.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Pure shape tests for the Generic JSON payload — the public contract receiver
 * developers depend on (parent #41, user story 14). Asserts the {@code version}
 * discriminator, snake_case keys, and the new-issue fields, independent of any
 * delivery or persistence.
 */
class GenericJsonFormatterTest {

	private final ObjectMapper mapper = new ObjectMapper();

	private final GenericJsonFormatter formatter = new GenericJsonFormatter(new ObjectMapper());

	private JsonNode format(NotificationOccurrence occurrence, NotificationContext context) {
		return mapper.readTree(formatter.format(occurrence, context));
	}

	@Test
	void newIssuePayloadCarriesVersionTypeAndSummary() {
		JsonNode payload = format(
				new NotificationOccurrence.NewIssue(42, 100, "TypeError: undefined is not a function",
						"main.js in handleClick", "production", Instant.parse("2026-07-20T10:00:00Z")),
				new NotificationContext("Shop", "shop", "https://outpost.example/issues/100"));

		assertThat(payload.get("version").asInt()).isEqualTo(1);
		assertThat(payload.get("type").asString()).isEqualTo("new_issue");
		assertThat(payload.get("project").get("id").asLong()).isEqualTo(42);
		assertThat(payload.get("project").get("slug").asString()).isEqualTo("shop");
		assertThat(payload.get("project").get("name").asString()).isEqualTo("Shop");
		JsonNode issue = payload.get("issue");
		assertThat(issue.get("id").asLong()).isEqualTo(100);
		assertThat(issue.get("title").asString()).isEqualTo("TypeError: undefined is not a function");
		assertThat(issue.get("culprit").asString()).isEqualTo("main.js in handleClick");
		assertThat(issue.get("environment").asString()).isEqualTo("production");
		assertThat(issue.get("first_seen").asString()).isEqualTo("2026-07-20T10:00:00Z");
		assertThat(issue.get("link").asString()).isEqualTo("https://outpost.example/issues/100");
	}

	@Test
	void keysAreSnakeCaseWithNoCamelCaseLeakage() {
		String json = formatter.format(
				new NotificationOccurrence.NewIssue(1, 2, "boom", "x", "dev", Instant.parse("2026-07-20T10:00:00Z")),
				new NotificationContext("P", "p", "https://o/issues/2"));

		assertThat(json).contains("first_seen").doesNotContain("firstSeen");
	}

	@Test
	void missingEnvironmentAndCulpritSerializeAsNull() {
		JsonNode payload = format(
				new NotificationOccurrence.NewIssue(1, 2, "boom", null, null, Instant.parse("2026-07-20T10:00:00Z")),
				new NotificationContext("P", "p", "https://o/issues/2"));

		JsonNode issue = payload.get("issue");
		assertThat(issue.get("environment").isNull()).isTrue();
		assertThat(issue.get("culprit").isNull()).isTrue();
	}

	@Test
	void testPayloadCarriesVersionTypeChannelAndMessage() {
		JsonNode payload = format(
				new NotificationOccurrence.Test(7, "Ops JSON", Instant.parse("2026-07-20T10:00:00Z")),
				new NotificationContext(null, null, "https://outpost.example/settings"));

		assertThat(payload.get("version").asInt()).isEqualTo(1);
		assertThat(payload.get("type").asString()).isEqualTo("test");
		assertThat(payload.get("channel").get("id").asLong()).isEqualTo(7);
		assertThat(payload.get("channel").get("name").asString()).isEqualTo("Ops JSON");
		assertThat(payload.get("message").asString()).contains("Ops JSON");
		assertThat(payload.get("fired_at").asString()).isEqualTo("2026-07-20T10:00:00Z");
		assertThat(payload.get("link").asString()).isEqualTo("https://outpost.example/settings");
	}

	@Test
	void incidentStartedCarriesMonitorProjectAndFailureReason() {
		JsonNode payload = format(
				new NotificationOccurrence.IncidentStarted(42, 9, "https://shop.example/health", "production", "HTTP 503",
						Instant.parse("2026-07-20T10:00:00Z")),
				new NotificationContext("Shop", "shop", "https://outpost.example/uptime"));

		assertThat(payload.get("version").asInt()).isEqualTo(1);
		assertThat(payload.get("type").asString()).isEqualTo("incident_started");
		assertThat(payload.get("project").get("id").asLong()).isEqualTo(42);
		assertThat(payload.get("project").get("slug").asString()).isEqualTo("shop");
		JsonNode monitor = payload.get("monitor");
		assertThat(monitor.get("id").asLong()).isEqualTo(9);
		assertThat(monitor.get("url").asString()).isEqualTo("https://shop.example/health");
		assertThat(monitor.get("environment").asString()).isEqualTo("production");
		assertThat(monitor.get("link").asString()).isEqualTo("https://outpost.example/uptime");
		JsonNode incident = payload.get("incident");
		assertThat(incident.get("failure_reason").asString()).isEqualTo("HTTP 503");
		assertThat(incident.get("opened_at").asString()).isEqualTo("2026-07-20T10:00:00Z");
		// Resolved-only fields are absent on a started payload.
		assertThat(incident.has("downtime_seconds")).isFalse();
	}

	@Test
	void incidentResolvedCarriesDowntimeSecondsAndHuman() {
		JsonNode payload = format(
				new NotificationOccurrence.IncidentResolved(42, 9, "https://shop.example/health", "production",
						Instant.parse("2026-07-20T10:00:00Z"), Instant.parse("2026-07-20T12:05:30Z"),
						Duration.ofSeconds(7530)),
				new NotificationContext("Shop", "shop", "https://outpost.example/uptime"));

		assertThat(payload.get("type").asString()).isEqualTo("incident_resolved");
		assertThat(payload.get("monitor").get("url").asString()).isEqualTo("https://shop.example/health");
		JsonNode incident = payload.get("incident");
		assertThat(incident.get("opened_at").asString()).isEqualTo("2026-07-20T10:00:00Z");
		assertThat(incident.get("closed_at").asString()).isEqualTo("2026-07-20T12:05:30Z");
		assertThat(incident.get("downtime_seconds").asLong()).isEqualTo(7530);
		assertThat(incident.get("downtime_human").asString()).isEqualTo("2h 5m 30s");
	}

	@Test
	void downtimeHumanOmitsHoursAndMinutesWhenZero() {
		JsonNode payload = format(
				new NotificationOccurrence.IncidentResolved(1, 2, "https://o/h", "dev",
						Instant.parse("2026-07-20T10:00:00Z"), Instant.parse("2026-07-20T10:00:45Z"),
						Duration.ofSeconds(45)),
				new NotificationContext("P", "p", "https://o/uptime"));

		assertThat(payload.get("incident").get("downtime_seconds").asLong()).isEqualTo(45);
		assertThat(payload.get("incident").get("downtime_human").asString()).isEqualTo("45s");
	}

	@Test
	void incidentStartedNullFailureReasonSerializesAsNull() {
		JsonNode payload = format(
				new NotificationOccurrence.IncidentStarted(1, 2, "https://o/h", "dev", null,
						Instant.parse("2026-07-20T10:00:00Z")),
				new NotificationContext("P", "p", "https://o/uptime"));

		assertThat(payload.get("incident").get("failure_reason").isNull()).isTrue();
	}
}
