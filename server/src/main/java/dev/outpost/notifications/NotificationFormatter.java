package dev.outpost.notifications;

/**
 * The channel-type formatter seam (issue #46, parent #41). One implementation
 * per channel type turns an occurrence into the request body that type's
 * receiver expects; {@link NotificationService} resolves the right one by
 * {@link #channelType()} and never knows the concrete shapes.
 *
 * <p>This is the seam the spec calls out: adding a channel type is a new
 * implementation of this interface and nothing else — no delivery, matching, or
 * history code changes, and no caller of the publisher changes. {@link
 * GenericJsonFormatter} formats the public {@code generic_json} contract;
 * {@link TeamsAdaptiveCardFormatter} formats {@code teams} Adaptive Cards.
 *
 * <p>Card/body layout is presentation, not a versioned contract (unlike the
 * Generic JSON payload): a formatter may change its output shape freely.
 */
public interface NotificationFormatter {

	/**
	 * The {@code notification_channel.type} this formatter renders (e.g.
	 * {@code "generic_json"}, {@code "teams"}). Used as the registry key.
	 */
	String channelType();

	/** Renders {@code occurrence} into the request body for one delivery to this type. */
	String format(NotificationOccurrence occurrence, NotificationContext context);
}
