package dev.outpost.notifications;

/**
 * The single seam between existing code and notification delivery: future trigger types are new
 * {@link NotificationOccurrence} variants, not new methods here. Delivery is best-effort and
 * fully asynchronous (ADR 0005) — {@code publish} must never block, slow, or fail its caller, and
 * any delivery error is swallowed rather than propagated back into the ingest or uptime paths.
 */
public interface NotificationPublisher {

	/** Report that a trigger occurred. Non-blocking; delivery happens off-thread. */
	void publish(NotificationOccurrence occurrence);
}
