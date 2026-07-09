package dev.outpost.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.ObjectMapper;
import dev.outpost.ingest.IngestItem;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TransactionPipelineTest {

	private final ObjectMapper mapper = new ObjectMapper();
	private final TransactionPipeline pipeline = new TransactionPipeline();

	private final Instant receivedAt = Instant.parse("2026-07-04T12:00:00Z");

	private static final String PAYLOAD = """
			{
			  "type": "transaction",
			  "transaction": "GET /api/tree/{id}",
			  "start_timestamp": 1783166399.0,
			  "timestamp": 1783166399.5,
			  "environment": "prod",
			  "release": "app@1.0.0",
			  "contexts": {"trace": {
			    "trace_id": "5b8efff798038103d269b633813fc60c",
			    "span_id": "b0e6f15b45c36285",
			    "op": "http.server",
			    "status": "ok"
			  }},
			  "spans": [{
			    "span_id": "aaaa000011112222",
			    "trace_id": "5b8efff798038103d269b633813fc60c",
			    "parent_span_id": "b0e6f15b45c36285",
			    "op": "http.client",
			    "description": "GET http://localhost:8081/api/tree",
			    "status": "ok",
			    "start_timestamp": 1783166399.1,
			    "timestamp": 1783166399.4,
			    "origin": "auto.http.browser",
			    "data": {"http.method": "GET", "url.full": "http://localhost:8081/api/tree"}
			  }]
			}""";

	@Test
	void stripsRedundantSpansArrayFromTransactionData() {
		ProcessedTransaction txn = process(PAYLOAD);

		// Child spans are their own rows; re-embedding spans[] in the payload just
		// doubles storage, so it must be gone. The rest of the payload stays.
		assertThat(txn.data().has("spans")).isFalse();
		assertThat(txn.data().path("transaction").asText()).isEqualTo("GET /api/tree/{id}");
		assertThat(txn.data().path("contexts").path("trace").path("op").asText()).isEqualTo("http.server");
		assertThat(txn.spans()).hasSize(1);
	}

	@Test
	void stripsPromotedColumnsFromSpanDataButKeepsAttributes() {
		ProcessedSpan span = process(PAYLOAD).spans().get(0);

		// Fields that became columns must not linger in the payload.
		for (String promoted : new String[] { "span_id", "trace_id", "parent_span_id", "op", "description", "status",
				"timestamp", "start_timestamp" }) {
			assertThat(span.data().has(promoted)).as("promoted key %s", promoted).isFalse();
		}
		// The useful part survives: the OTel attribute bag and origin.
		assertThat(span.data().path("origin").asText()).isEqualTo("auto.http.browser");
		assertThat(span.data().path("data").path("http.method").asText()).isEqualTo("GET");

		// …and the columns still carry those values.
		assertThat(span.spanId()).isEqualTo("aaaa000011112222");
		assertThat(span.op()).isEqualTo("http.client");
		assertThat(span.description()).isEqualTo("GET http://localhost:8081/api/tree");
		assertThat(span.status()).isEqualTo("ok");
	}

	private ProcessedTransaction process(String payload) {
		return pipeline.process(new IngestItem.TransactionEvent(1L, receivedAt, mapper.readTree(payload)));
	}
}
