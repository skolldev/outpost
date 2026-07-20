package dev.outpost.notifications;

import java.time.Duration;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Formatting helpers shared across {@link NotificationFormatter} implementations
 * (issue #46). Pure presentation with no contract semantics — the Generic JSON
 * and Teams formatters both render downtime the same way and both serialize a
 * built node the same way, so the logic lives here rather than being copied into
 * each. Package-private: an implementation detail of the notifications module.
 */
final class NotificationText {

	private NotificationText() {
	}

	/**
	 * Compact human-readable duration, e.g. {@code "2h 5m 30s"}. Hours and minutes
	 * are omitted while zero (so a sub-minute outage reads {@code "45s"}).
	 */
	static String humanDuration(Duration downtime) {
		long seconds = Math.max(0, downtime.toSeconds());
		long hours = seconds / 3600;
		long minutes = (seconds % 3600) / 60;
		long secs = seconds % 60;
		StringBuilder out = new StringBuilder();
		if (hours > 0) {
			out.append(hours).append("h ");
		}
		if (hours > 0 || minutes > 0) {
			out.append(minutes).append("m ");
		}
		out.append(secs).append('s');
		return out.toString();
	}

	/**
	 * Serializes a built node to a string, turning the checked Jackson failure into
	 * an unchecked one. A build-then-serialize node is serializable by construction,
	 * so a throw here is a programming error, not a delivery condition.
	 */
	static String serialize(ObjectMapper mapper, ObjectNode root) {
		try {
			return mapper.writeValueAsString(root);
		}
		catch (JacksonException e) {
			throw new IllegalStateException("notification payload not serializable", e);
		}
	}
}
