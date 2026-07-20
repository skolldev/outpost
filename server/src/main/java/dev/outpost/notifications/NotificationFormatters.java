package dev.outpost.notifications;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Resolves the {@link NotificationFormatter} for a channel type (issue #46).
 * Spring injects every formatter bean; this indexes them by
 * {@link NotificationFormatter#channelType()} so {@link NotificationService}
 * looks one up by the channel's stored {@code type} without a hardcoded switch.
 *
 * <p>This is what keeps the formatter seam a seam: a new channel type is a new
 * {@link NotificationFormatter} bean and appears here automatically — the
 * registry, the delivery path, and the callers are untouched.
 */
@Component
public class NotificationFormatters {

	private final Map<String, NotificationFormatter> byType;

	public NotificationFormatters(List<NotificationFormatter> formatters) {
		this.byType = formatters.stream()
			.collect(Collectors.toUnmodifiableMap(NotificationFormatter::channelType, Function.identity()));
	}

	/**
	 * Formats {@code occurrence} for {@code channelType}.
	 *
	 * @throws IllegalArgumentException if no formatter is registered for the type —
	 * a programming error (the type CHECK constraint and controller validation keep
	 * only known types in the table), surfaced rather than silently dropped.
	 */
	public String format(String channelType, NotificationOccurrence occurrence, NotificationContext context) {
		NotificationFormatter formatter = byType.get(channelType);
		if (formatter == null) {
			throw new IllegalArgumentException("no notification formatter for channel type: " + channelType);
		}
		return formatter.format(occurrence, context);
	}
}
