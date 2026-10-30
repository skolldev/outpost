package dev.outpost.support;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Builds SDK-shaped Sentry envelopes for tests and the load benchmark; thread-safe,
 * so one instance can be shared across benchmark workers. Every event gets a fresh
 * {@code event_id} — a repeat would silently no-op against {@code EventStore}'s
 * {@code ON CONFLICT DO NOTHING} and inflate throughput numbers.
 */
public final class EnvelopeFactory {

	/** Enough distinct issues that no single {@code issue} row is the bottleneck. */
	public static final int DEFAULT_DISTINCT_FINGERPRINTS = 50;

	private final int distinctFingerprints;

	private final AtomicLong sequence = new AtomicLong();

	public EnvelopeFactory() {
		this(DEFAULT_DISTINCT_FINGERPRINTS);
	}

	public EnvelopeFactory(int distinctFingerprints) {
		if (distinctFingerprints < 1) {
			throw new IllegalArgumentException("distinctFingerprints must be at least 1");
		}
		this.distinctFingerprints = distinctFingerprints;
	}

	/**
	 * A Java-SDK-shaped error event. The in-app frame's function name cycles over
	 * {@code distinctFingerprints}, which is what {@code Fingerprinter} groups on.
	 */
	public String error(String environment) {
		long n = sequence.getAndIncrement();
		String event = """
				{"event_id":"%s","timestamp":"%s","platform":"java","level":"error","environment":"%s",\
				"release":"bench@1.0.0","logentry":{"formatted":"Unhandled exception in request"},\
				"contexts":{"trace":{"trace_id":"%s","span_id":"%s"}},\
				"exception":{"values":[{"type":"IllegalStateException","value":"order has no customer",\
				"module":"java.lang","stacktrace":{"frames":[\
				{"module":"org.apache.catalina.core.StandardEngineValve","function":"invoke","in_app":false,"lineno":74},\
				{"module":"dev.bench.OrderService","function":"loadCustomer%d","in_app":true,"lineno":88}\
				]}}]}}""".formatted(hexId(), Instant.now(), environment, hexId(), spanId(n),
				n % distinctFingerprints);
		return envelope("event", event);
	}

	/**
	 * A log item carrying {@code records} log records — the shape the SDKs ship,
	 * which batches up to 100 records per item. Throughput for logs is therefore
	 * best reported per record, not per envelope.
	 */
	public String log(String environment, int records) {
		double now = System.currentTimeMillis() / 1000.0;
		StringBuilder payload = new StringBuilder("{\"items\":[");
		for (int i = 0; i < records; i++) {
			if (i > 0) {
				payload.append(',');
			}
			payload.append("""
					{"timestamp":%s,"trace_id":"%s","level":"info","severity_number":9,\
					"body":"handling checkout step %d",\
					"attributes":{"sentry.environment":{"value":"%s","type":"string"},\
					"sentry.release":{"value":"bench@1.0.0","type":"string"},\
					"logger.name":{"value":"dev.bench.OrderService","type":"string"}}}"""
				.formatted(now, hexId(), i, environment));
		}
		payload.append("]}");
		String body = payload.toString();
		return "{\"sent_at\":\"" + Instant.now() + "\"}\n"
				+ "{\"type\":\"log\",\"item_count\":" + records
				+ ",\"content_type\":\"application/vnd.sentry.items.log+json\",\"length\":" + utf8Length(body) + "}\n"
				+ body + "\n";
	}

	/** A server transaction with {@code spans} child spans, all on one trace. */
	public String transaction(String environment, int spans) {
		long n = sequence.getAndIncrement();
		Instant start = Instant.now().minusMillis(400);
		Instant end = start.plusMillis(120);
		String traceId = hexId();
		String rootSpan = spanId(n);
		StringBuilder children = new StringBuilder();
		for (int i = 0; i < spans; i++) {
			if (i > 0) {
				children.append(',');
			}
			children.append("""
					{"trace_id":"%s","span_id":"%s","parent_span_id":"%s","op":"db.sql.query",\
					"description":"SELECT * FROM orders WHERE id = ?","start_timestamp":%s,"timestamp":%s,\
					"status":"ok"}"""
				.formatted(traceId, spanId(n * 1000L + i + 1), rootSpan, epochSeconds(start.plusMillis(20)),
						epochSeconds(start.plusMillis(90))));
		}
		String txn = """
				{"type":"transaction","transaction":"GET /api/checkout","platform":"java","environment":"%s",\
				"release":"bench@1.0.0","start_timestamp":%s,"timestamp":%s,\
				"contexts":{"trace":{"trace_id":"%s","span_id":"%s","op":"http.server","status":"ok"}},\
				"spans":[%s]}""".formatted(environment, epochSeconds(start), epochSeconds(end), traceId, rootSpan,
				children);
		return envelope("transaction", txn);
	}

	/**
	 * A v2 span item as the JS SDK (v11+) streams it: {@code spans} children of one
	 * pageload segment, the segment last as it finishes last. Like the SDK, the item
	 * header carries no {@code length}, so the payload is newline-delimited.
	 */
	public String streamedSpans(String environment, int spans) {
		long n = sequence.getAndIncrement();
		Instant start = Instant.now().minusMillis(400);
		String traceId = hexId();
		String segmentId = spanId(n);
		StringBuilder items = new StringBuilder();
		for (int i = 0; i < spans; i++) {
			items.append(streamedSpan(traceId, spanId(n * 1000L + i + 1), segmentId, segmentId, "GET api.example.com",
					"http.client", false, start.plusMillis(20), start.plusMillis(90), environment)).append(',');
		}
		items.append(streamedSpan(traceId, segmentId, null, segmentId, "/products/:id", "pageload", true, start,
				start.plusMillis(120), environment));
		return streamedSpanEnvelope(traceId, spans + 1, items.toString());
	}

	/** One streamed span with the attributes the JS SDK puts on every span. */
	public static String streamedSpan(String traceId, String spanId, String parentSpanId, String segmentId,
			String name, String op, boolean segment, Instant start, Instant end, String environment) {
		return """
				{"trace_id":"%s","span_id":"%s",%s"name":"%s","start_timestamp":%s,"end_timestamp":%s,\
				"status":"ok","is_segment":%s,"attributes":{\
				"sentry.op":{"type":"string","value":"%s"},\
				"sentry.origin":{"type":"string","value":"auto"},\
				"sentry.environment":{"type":"string","value":"%s"},\
				"sentry.release":{"type":"string","value":"bench@1.0.0"},\
				"sentry.segment.id":{"type":"string","value":"%s"},\
				"sentry.sdk.name":{"type":"string","value":"sentry.javascript.angular"},\
				"sentry.sdk.version":{"type":"string","value":"11.8.0"}}}"""
			.formatted(traceId, spanId, parentSpanId == null ? "" : "\"parent_span_id\":\"" + parentSpanId + "\",",
					name, epochSeconds(start), epochSeconds(end), segment, op, environment, segmentId);
	}

	/** Wraps comma-joined {@link #streamedSpan} JSON objects in a v2 span envelope. */
	public static String streamedSpanEnvelope(String traceId, int itemCount, String items) {
		return "{\"sent_at\":\"" + Instant.now() + "\",\"trace\":{\"trace_id\":\"" + traceId
				+ "\"},\"sdk\":{\"name\":\"sentry.javascript.angular\",\"version\":\"11.8.0\"}}\n"
				+ "{\"type\":\"span\",\"item_count\":" + itemCount
				+ ",\"content_type\":\"application/vnd.sentry.items.span.v2+json\"}\n"
				+ "{\"version\":2,\"ingest_settings\":{\"infer_ip\":\"auto\",\"infer_user_agent\":\"auto\"},\"items\":["
				+ items + "]}\n";
	}

	private static String envelope(String type, String item) {
		return "{\"event_id\":\"" + hexId() + "\",\"sent_at\":\"" + Instant.now() + "\"}\n" + "{\"type\":\"" + type
				+ "\",\"length\":" + utf8Length(item) + "}\n" + item + "\n";
	}

	private static int utf8Length(String s) {
		return s.getBytes(StandardCharsets.UTF_8).length;
	}

	private static double epochSeconds(Instant instant) {
		return instant.toEpochMilli() / 1000.0;
	}

	private static String spanId(long n) {
		return String.format("%016x", n);
	}

	private static String hexId() {
		return UUID.randomUUID().toString().replace("-", "");
	}

}
