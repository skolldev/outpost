package dev.outpost.notifications;

/**
 * The single choke-point seam between existing code and notification delivery
 * (parent #41). One method, one argument: existing code reports that a trigger
 * occurred and returns immediately. There is intentionally no other coupling
 * point — future trigger types are new {@link NotificationOccurrence} variants,
 * not new methods here.
 *
 * <p>Delivery is best-effort and fully asynchronous (ADR 0005): {@code publish}
 * must never block, slow, or fail its caller. A dead receiver, a full executor,
 * or any delivery error is swallowed and (where possible) recorded, never
 * propagated back across the seam into the ingest or uptime paths.
 */
public interface NotificationPublisher {

	/** Report that a trigger occurred. Non-blocking; delivery happens off-thread. */
	void publish(NotificationOccurrence occurrence);
}
