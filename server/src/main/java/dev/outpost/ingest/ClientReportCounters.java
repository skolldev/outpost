package dev.outpost.ingest;

import tools.jackson.databind.JsonNode;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import org.springframework.stereotype.Component;

/**
 * In-memory counters for SDK client reports (§4.2): what the SDKs dropped
 * client-side, keyed by {@code reason:category}. Persistence and the stats UI
 * arrive in Phase 5.
 */
@Component
public class ClientReportCounters {

	private final Map<String, LongAdder> discarded = new ConcurrentHashMap<>();

	public void record(long projectId, JsonNode clientReport) {
		JsonNode events = clientReport.get("discarded_events");
		if (events == null || !events.isArray()) {
			return;
		}
		for (JsonNode entry : events) {
			String key = projectId + ":" + entry.path("reason").asText("unknown") + ":"
					+ entry.path("category").asText("unknown");
			discarded.computeIfAbsent(key, k -> new LongAdder()).add(entry.path("quantity").asLong(1));
		}
	}

	public Map<String, Long> snapshot() {
		Map<String, Long> out = new ConcurrentHashMap<>();
		discarded.forEach((k, v) -> out.put(k, v.sum()));
		return out;
	}
}
