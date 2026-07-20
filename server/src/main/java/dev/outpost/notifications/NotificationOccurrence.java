package dev.outpost.notifications;

import java.time.Instant;

/**
 * A trigger occurrence handed across the publisher seam (parent #41): the one
 * thing existing code tells the notifications module about. Callers construct an
 * occurrence and pass it to {@link NotificationPublisher#publish}; everything
 * behind the seam — channel matching, formatting, history, delivery — is opaque
 * to them.
 *
 * <p>Sealed so the formatter/delivery switch is exhaustive. This slice ships
 * {@link NewIssue} only; {@code incident_started}/{@code incident_resolved} and
 * {@code test} are additive later variants (#44, #45) that reuse the same seam.
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
}
