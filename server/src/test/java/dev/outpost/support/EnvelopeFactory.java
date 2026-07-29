package dev.outpost.support;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Builds SDK-shaped Sentry envelopes for tests and the load benchmark. Shapes
 * are lifted from the real SDK payloads asserted in {@code
 * EnvelopeIngestIntegrationTest}, {@code LogIngestIntegrationTest} and {@code
 * TraceQueryIntegrationTest}, which remain the source of truth for compatibility.
 *
 * <p>Two generation details decide whether a benchmark built on this measures
 * anything real:
 *
 * <ul>
 * <li><b>Every event gets a fresh {@code event_id}.</b> {@code EventStore}'s
 * insert is {@code ON CONFLICT DO NOTHING}, so a repeated id would time a no-op
 * insert and quietly report a throughput the storage path never achieved.
 * <li><b>{@code distinctFingerprints} is a constructor argument, and defaults
 * above one.</b> Events sharing a fingerprint all contend on a single
 * {@code issue} row inside the per-project advisory lock — a degenerate hot spot,
 * not production shape. Pass {@code 1} deliberately to measure that worst case.
 * </ul>
 *
 * <p>Instances are thread-safe: the only mutable state is a counter.
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
