package dev.outpost.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.ObjectMapper;
import dev.outpost.ingest.IngestItem;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class StreamedSpanPipelineTest {

	private static final String TRACE_ID = "5b8efff798038103d269b633813fc60c";

	private final ObjectMapper mapper = new ObjectMapper();
	private final StreamedSpanPipeline pipeline = new StreamedSpanPipeline();

	private final Instant receivedAt = Instant.parse("2026-07-04T12:00:00Z");

	/** Shaped like @sentry/angular 11.8.0 output: a pageload segment, one child, one INP span. */
	private static final String PAYLOAD = """
			{"version": 2,
			 "ingest_settings": {"infer_ip": "auto", "infer_user_agent": "auto"},
			 "items": [
			  {"trace_id": "5b8efff798038103d269b633813fc60c", "span_id": "b0e6f15b45c36285",
			   "name": "/products/:id", "start_timestamp": 1783166399.0, "end_timestamp": 1783166399.5,
			   "status": "ok", "is_segment": true,
			   "attributes": {
			     "sentry.op": {"type": "string", "value": "pageload"},
			     "sentry.origin": {"type": "string", "value": "auto.pageload.angular"},
			     "sentry.environment": {"type": "string", "value": "prod"},
			     "sentry.release": {"type": "string", "value": "shop@1.5.0"},
			     "sentry.segment.id": {"type": "string", "value": "b0e6f15b45c36285"},
			     "sentry.segment.name": {"type": "string", "value": "/products/:id"},
			     "sentry.sdk.name": {"type": "string", "value": "sentry.javascript.angular"},
			     "sentry.sdk.integrations": {"type": "array", "value": ["BrowserTracing", "Dedupe"]}}},
			  {"trace_id": "5b8efff798038103d269b633813fc60c", "span_id": "aaaa000011112222",
			   "parent_span_id": "b0e6f15b45c36285", "name": "GET api.example.com",
			   "start_timestamp": 1783166399.1, "end_timestamp": 1783166399.4, "status": "error", "is_segment": false,
			   "attributes": {
			     "sentry.op": {"type": "string", "value": "http.client"},
			     "sentry.segment.id": {"type": "string", "value": "b0e6f15b45c36285"},
			     "http.request.method": {"type": "string", "value": "GET"},
			     "http.response.status_code": {"type": "integer", "value": 503}},
			   "links": [{"trace_id": "0af7651916cd43dd8448eb211c80319c", "span_id": "b7ad6b7169203331"}]},
			  {"trace_id": "5b8efff798038103d269b633813fc60c", "span_id": "cccc000011112222",
			   "name": "Click", "start_timestamp": 1783166401.0, "end_timestamp": 1783166401.2,
			   "status": "ok", "is_segment": true,
			   "attributes": {
			     "sentry.op": {"type": "string", "value": "ui.interaction.click"},
			     "sentry.segment.id": {"type": "string", "value": "cccc000011112222"},
			     "browser.web_vital.inp.value": {"type": "double", "value": 200.0}}}
			 ]}""";

	@Test
	void segmentBecomesTransactionWithTraceIdentityAndAttributes() {
		StreamedSpans result = process(PAYLOAD);

		assertThat(result.segments()).hasSize(1);
		ProcessedTransaction txn = result.segments().getFirst();
		assertThat(txn.traceId()).isEqualTo(TRACE_ID);
		assertThat(txn.spanId()).isEqualTo("b0e6f15b45c36285");
		assertThat(txn.parentSpanId()).isNull();
		assertThat(txn.name()).isEqualTo("/products/:id");
		assertThat(txn.op()).isEqualTo("pageload");
		assertThat(txn.environment()).isEqualTo("prod");
		assertThat(txn.release()).isEqualTo("shop@1.5.0");
		assertThat(txn.status()).isEqualTo("ok");
		assertThat(txn.startTs()).isEqualTo(Instant.ofEpochMilli(1783166399000L));
		assertThat(txn.durationMs()).isEqualTo(500.0);
		assertThat(txn.spans()).isEmpty();
		assertThat(txn.data().path("data").path("sentry.sdk.name").asText()).isEqualTo("sentry.javascript.angular");
		assertThat(txn.data().path("data").path("sentry.sdk.integrations").isArray()).isTrue();
	}

	@Test
	void childSpanPointsAtItsSegmentsTransactionWithoutIt() {
		StreamedSpans result = process(PAYLOAD);
		ProcessedTransaction txn = result.segments().getFirst();

		ProcessedSpan child = result.spans().stream()
			.filter(span -> span.spanId().equals("aaaa000011112222"))
			.findFirst()
			.orElseThrow();
		assertThat(child.txnId()).isEqualTo(txn.id());
		assertThat(child.parentSpanId()).isEqualTo("b0e6f15b45c36285");
		assertThat(child.op()).isEqualTo("http.client");
		assertThat(child.description()).isEqualTo("GET api.example.com");
		assertThat(child.status()).isEqualTo("error");
		assertThat(child.data().path("data").path("http.response.status_code").asInt()).isEqualTo(503);
		assertThat(child.data().path("links").isArray()).isTrue();
		for (String promoted : new String[] { "trace_id", "span_id", "parent_span_id", "name", "start_timestamp",
				"end_timestamp", "status", "is_segment", "attributes" }) {
			assertThat(child.data().has(promoted)).as("promoted key %s", promoted).isFalse();
		}
	}

	@Test
	void webVitalSegmentIsStoredAsSpanNotTransaction() {
		StreamedSpans result = process(PAYLOAD);

		assertThat(result.segments()).extracting(ProcessedTransaction::spanId).doesNotContain("cccc000011112222");
		ProcessedSpan inp = result.spans().stream()
			.filter(span -> span.spanId().equals("cccc000011112222"))
			.findFirst()
			.orElseThrow();
		assertThat(inp.op()).isEqualTo("ui.interaction.click");
		assertThat(inp.data().path("data").path("browser.web_vital.inp.value").asDouble()).isEqualTo(200.0);
	}

	@Test
	void redeliveredItemMapsToTheSameIds() {
		StreamedSpans first = process(PAYLOAD);
		StreamedSpans second = process(PAYLOAD);

		assertThat(second.segments().getFirst().id()).isEqualTo(first.segments().getFirst().id());
		assertThat(second.spans()).extracting(ProcessedSpan::id)
			.containsExactlyElementsOf(first.spans().stream().map(ProcessedSpan::id).toList());
	}

	@Test
	void sameSpanIdInAnotherProjectGetsAnotherId() {
		ProcessedTransaction here = process(PAYLOAD).segments().getFirst();
		ProcessedTransaction there = pipeline
			.process(new IngestItem.SpanBatch(2L, receivedAt, mapper.readTree(PAYLOAD)))
			.segments()
			.getFirst();

		assertThat(there.id()).isNotEqualTo(here.id());
	}

	@Test
	void malformedSpanIsDroppedAndSiblingsKept() {
		StreamedSpans result = process("""
				{"items": [
				  {"span_id": "aaaa000011112222", "name": "no trace", "start_timestamp": 1783166399.0,
				   "end_timestamp": 1783166399.1, "status": "ok", "is_segment": false, "attributes": {}},
				  "not a span",
				  {"trace_id": "5b8efff798038103d269b633813fc60c", "span_id": "bbbb000011112222", "name": "kept",
				   "start_timestamp": 1783166399.0, "end_timestamp": 1783166399.1, "status": "ok",
				   "is_segment": false, "attributes": {}}
				]}""");

		assertThat(result.segments()).isEmpty();
		assertThat(result.spans()).extracting(ProcessedSpan::spanId).containsExactly("bbbb000011112222");
	}

	@Test
	void childWithoutSegmentIdIsStillKept() {
		ProcessedSpan span = process("""
				{"items": [{"trace_id": "5b8efff798038103d269b633813fc60c", "span_id": "bbbb000011112222",
				  "parent_span_id": "b0e6f15b45c36285", "name": "orphan", "start_timestamp": 1783166399.0,
				  "end_timestamp": 1783166399.1, "status": "ok", "is_segment": false, "attributes": {}}]}""")
			.spans()
			.getFirst();

		assertThat(span.txnId()).isNotNull();
		assertThat(span.parentSpanId()).isEqualTo("b0e6f15b45c36285");
	}

	@Test
	void segmentDefaultsWhenEnvironmentAndNameAreMissing() {
		ProcessedTransaction txn = process("""
				{"items": [{"trace_id": "5b8efff798038103d269b633813fc60c", "span_id": "b0e6f15b45c36285",
				  "start_timestamp": 1783166399.0, "end_timestamp": 1783166398.0, "status": "ok", "is_segment": true,
				  "attributes": {"sentry.segment.name": {"type": "string", "value": "GET /health"}}}]}""")
			.segments()
			.getFirst();

		assertThat(txn.environment()).isEqualTo("production");
		assertThat(txn.name()).isEqualTo("GET /health");
		assertThat(txn.op()).isNull();
		// End before start (clock skew within a span) collapses to zero duration.
		assertThat(txn.durationMs()).isZero();
	}

	private StreamedSpans process(String payload) {
		return pipeline.process(new IngestItem.SpanBatch(1L, receivedAt, mapper.readTree(payload)));
	}
}
