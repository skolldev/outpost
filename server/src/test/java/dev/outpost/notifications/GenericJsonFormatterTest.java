package dev.outpost.notifications;

import static org.assertj.core.api.Assertions.assertThat;

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
}
