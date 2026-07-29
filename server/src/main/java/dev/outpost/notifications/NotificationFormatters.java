package dev.outpost.notifications;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Resolves the {@link NotificationFormatter} for a channel type. Spring injects
 * every formatter bean and this indexes them by
 * {@link NotificationFormatter#channelType()}, so a new formatter registers
 * itself and {@link NotificationService} needs no hardcoded switch.
 */
@Component
public class NotificationFormatters {

	private final Map<String, NotificationFormatter> byType;

	public NotificationFormatters(List<NotificationFormatter> formatters) {
		this.byType = formatters.stream()
			.collect(Collectors.toUnmodifiableMap(NotificationFormatter::channelType, Function.identity()));
	}

	/**
	 * @throws IllegalArgumentException if no formatter is registered for the type — a
	 * programming error, since the CHECK constraint and controller validation keep
	 * only known types in the table. Surfaced rather than silently dropped.
	 */
	public String format(String channelType, NotificationOccurrence occurrence, NotificationContext context) {
		NotificationFormatter formatter = byType.get(channelType);
		if (formatter == null) {
			throw new IllegalArgumentException("no notification formatter for channel type: " + channelType);
		}
		return formatter.format(occurrence, context);
	}
}
