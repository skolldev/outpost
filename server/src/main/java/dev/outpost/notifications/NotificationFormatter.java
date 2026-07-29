package dev.outpost.notifications;

/**
 * The channel-type formatter seam (#46). One implementation per channel type
 * turns an occurrence into the request body that type's receiver expects;
 * {@link NotificationService} resolves one by {@link #channelType()} and never
 * knows the concrete shapes. Adding a channel type is a new implementation of
 * this interface and nothing else — no delivery, matching, or history changes,
 * and no caller of the publisher changes.
 *
 * <p>Body layout is presentation, not a versioned contract: a formatter may
 * change its output shape freely. The one exception is
 * {@link GenericJsonFormatter}, whose payload is a public versioned contract.
 */
public interface NotificationFormatter {

	/** The {@code notification_channel.type} this formatter renders; the registry key. */
	String channelType();

	String format(NotificationOccurrence occurrence, NotificationContext context);
}
