package dev.outpost.notifications;

/**
 * Module-side enrichment for an occurrence: the Project display fields and the
 * pre-built deep link the formatter needs but the seam's caller does not hold.
 * Resolved inside the notifications module (from the Project row and the
 * {@code outpost.public-url} configuration) so callers stay ignorant of payload
 * shape and of the public-url setting.
 */
public record NotificationContext(String projectName, String projectSlug, String link) {
}
