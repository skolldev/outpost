package dev.outpost.notifications;

/**
 * Module-side enrichment for an occurrence — what the formatter needs but the
 * seam's caller does not hold. Resolved inside the notifications module so
 * callers stay ignorant of payload shape and of the public-url setting.
 */
public record NotificationContext(String projectName, String projectSlug, String link) {
}
