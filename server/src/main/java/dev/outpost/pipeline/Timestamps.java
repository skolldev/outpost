package dev.outpost.pipeline;

import tools.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

/**
 * SDK timestamp handling shared by the error and log pipelines: parse the two
 * wire formats (epoch seconds or ISO-8601) and clamp wildly skewed client
 * clocks to the server's received-at.
 */
final class Timestamps {

	private static final Duration MAX_PAST_SKEW = Duration.ofDays(30);
	private static final Duration MAX_FUTURE_SKEW = Duration.ofHours(1);

	private Timestamps() {
	}

	static Instant parse(JsonNode timestamp) {
		if (timestamp == null) {
			return null;
		}
		if (timestamp.isNumber()) {
			double seconds = timestamp.asDouble();
			return Instant.ofEpochMilli(Math.round(seconds * 1000));
		}
		try {
			return Instant.parse(timestamp.asText());
		}
		catch (DateTimeParseException e) {
			try {
				return OffsetDateTime.parse(timestamp.asText()).toInstant();
			}
			catch (DateTimeParseException e2) {
				return null;
			}
		}
	}

	static Instant clamp(Instant timestamp, Instant receivedAt) {
		if (timestamp == null || timestamp.isBefore(receivedAt.minus(MAX_PAST_SKEW))
				|| timestamp.isAfter(receivedAt.plus(MAX_FUTURE_SKEW))) {
			return receivedAt;
		}
		return timestamp;
	}
}
