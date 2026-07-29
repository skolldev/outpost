package dev.outpost.notifications;

import java.time.Duration;
import java.time.Instant;

/**
 * A trigger occurrence handed across the publisher seam (#41): the one thing
 * existing code tells the notifications module about. Everything behind the seam
 * — channel matching, formatting, history, delivery — is opaque to callers.
 *
 * <p>Sealed so the formatter/delivery switch is exhaustive.
 */
public sealed interface NotificationOccurrence {

	/** The stored trigger type discriminator (matches {@code notification_channel.triggers}). */
	String triggerType();

	/**
	 * The first Event of a fingerprint created a new Issue. Carries only facts the
	 * caller already holds; the module enriches with the Project name/slug the
	 * payload needs, so the caller stays ignorant of payload shape.
	 *
	 * @param environment may be {@code null} when the Event carried none; that
	 * then matches only a channel with an empty Environment filter.
	 */
	record NewIssue(long projectId, long issueId, String title, String culprit, String environment,
			Instant firstSeen) implements NotificationOccurrence {

		@Override
		public String triggerType() {
			return "new_issue";
		}
	}

	/**
	 * An Uptime Monitor's third consecutive failed check opened an Incident (#45).
	 * Fired exactly when the incident row is inserted — not on every failed check,
	 * and not when an edit re-arms an already-open incident. Monitors have no name
	 * column, so {@code monitorUrl} is both the identity and the payload's URL.
	 *
	 * @param environment always present ({@code uptime_monitor.environment} is
	 * {@code NOT NULL}), unlike {@link NewIssue}'s.
	 * @param failureReason HTTP status or connection error (e.g. {@code "HTTP 503"});
	 * may be {@code null} if none was recorded.
	 */
	record IncidentStarted(long projectId, long monitorId, String monitorUrl, String environment,
			String failureReason, Instant openedAt) implements NotificationOccurrence {

		@Override
		public String triggerType() {
			return "incident_started";
		}
	}

	/**
	 * The next successful check closed an open Incident. Fired only when the close
	 * actually transitions an open incident, not on every success while healthy.
	 *
	 * @param downtime {@code closedAt − openedAt}; formatted for the payload as
	 * whole seconds plus a human-readable string.
	 */
	record IncidentResolved(long projectId, long monitorId, String monitorUrl, String environment, Instant openedAt,
			Instant closedAt, Duration downtime) implements NotificationOccurrence {

		@Override
		public String triggerType() {
			return "incident_resolved";
		}
	}

	/**
	 * An Admin verifying a channel (#44). Never stored in
	 * {@code notification_channel.triggers}, and bypasses channel matching (the
	 * target is named directly) — but still formatted and delivered through the
	 * same pipeline, so a successful test proves the whole path end to end.
	 *
	 * @param channelName echoed in the payload so the receiver can confirm which
	 * channel was verified.
	 */
	record Test(long channelId, String channelName, Instant firedAt) implements NotificationOccurrence {

		@Override
		public String triggerType() {
			return "test";
		}

		/** Shared by every per-type formatter. */
		public String message() {
			return "Test notification from Outpost — the channel \"" + channelName + "\" is configured correctly.";
		}
	}
}
