package dev.outpost.notifications;

import java.time.Duration;
import java.time.Instant;

/**
 * A trigger occurrence handed across the publisher seam (parent #41): the one
 * thing existing code tells the notifications module about. Callers construct an
 * occurrence and pass it to {@link NotificationPublisher#publish}; everything
 * behind the seam — channel matching, formatting, history, delivery — is opaque
 * to them.
 *
 * <p>Sealed so the formatter/delivery switch is exhaustive. Ships {@link NewIssue}
 * and {@link Test} (#43/#44) plus the Uptime incident variants
 * {@link IncidentStarted} and {@link IncidentResolved} (#45) — all reusing the
 * same seam, callers unchanged.
 */
public sealed interface NotificationOccurrence {

	/** The stored trigger type discriminator (matches {@code notification_channel.triggers}). */
	String triggerType();

	/**
	 * The first Event of a fingerprint created a new Issue. Carries only the
	 * domain facts the seam's one caller ({@code EventStore}) already holds; the
	 * module enriches with the Project name/slug it needs for the payload, so
	 * the caller stays ignorant of payload shape.
	 *
	 * @param environment may be {@code null} when the Event carried none; per the
	 * filter semantics that then matches only a channel with an empty Environment
	 * filter.
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
	 * Fired by {@code UptimeCheckService} exactly when the incident row is inserted
	 * — not on every failed check, and not when an edit re-arms an already-open
	 * incident. Monitors are identified by their probed URL (no name column;
	 * CONTEXT.md defines a Monitor as a configuration that probes one URL), so
	 * {@code monitorUrl} is both the identity and the probed URL in the payload.
	 *
	 * @param environment the monitor's Environment (always present —
	 * {@code uptime_monitor.environment} is {@code NOT NULL}), matched against
	 * channel Environment filters like any other occurrence.
	 * @param failureReason the failing check's error, HTTP status or connection
	 * error (e.g. {@code "HTTP 503"}); may be {@code null} if none was recorded.
	 */
	record IncidentStarted(long projectId, long monitorId, String monitorUrl, String environment,
			String failureReason, Instant openedAt) implements NotificationOccurrence {

		@Override
		public String triggerType() {
			return "incident_started";
		}
	}

	/**
	 * The next successful check closed an open Incident (#45). Fired by
	 * {@code UptimeCheckService} exactly when the close actually transitions an
	 * open incident (not on every success while already healthy). Carries the
	 * downtime — {@code closedAt − openedAt} — in addition to the started fields.
	 *
	 * @param downtime how long the Incident was open; formatted for the payload as
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
	 * An Admin verifying a channel: fired only by the test-send action (#44),
	 * never stored in {@code notification_channel.triggers}. It bypasses channel
	 * matching entirely (the target channel is named directly) but is still
	 * formatted and delivered through the same pipeline, so a successful test
	 * proves the whole path end to end. The message is a fixed human-readable
	 * confirmation string the receiver can display.
	 *
	 * @param channelName the target channel's display name, echoed in the payload
	 * so the receiver can confirm which channel was verified.
	 */
	record Test(long channelId, String channelName, Instant firedAt) implements NotificationOccurrence {

		@Override
		public String triggerType() {
			return "test";
		}

		/** The human-readable confirmation string, shared by every per-type formatter. */
		public String message() {
			return "Test notification from Outpost — the channel \"" + channelName + "\" is configured correctly.";
		}
	}
}
