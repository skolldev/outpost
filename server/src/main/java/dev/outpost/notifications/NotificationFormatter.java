package dev.outpost.notifications;

/**
 * The channel-type formatter seam: one implementation per channel type turns an occurrence into
 * the request body that type's receiver expects, resolved by {@link NotificationService} via
 * {@link #channelType()}. Body layout is presentation and free to change, except
 * {@link GenericJsonFormatter}'s payload, which is a public versioned contract.
 */
public interface NotificationFormatter {

	/** The {@code notification_channel.type} this formatter renders; the registry key. */
	String channelType();

	String format(NotificationOccurrence occurrence, NotificationContext context);
}
