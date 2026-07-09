package dev.outpost.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.ObjectMapper;
import dev.outpost.ingest.IngestItem;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class LogPipelineTest {

	private final ObjectMapper mapper = new ObjectMapper();
	private final LogPipeline pipeline = new LogPipeline(mapper);

	private final Instant receivedAt = Instant.parse("2026-07-04T12:00:00Z");

	@Test
	void unpacksBatchAndFlattensTypedAttributes() {
		List<ProcessedLog> records = process("""
				{"items":[{
				  "timestamp": 1783166399.5,
				  "trace_id": "5b8efff798038103d269b633813fc60c",
				  "level": "warn",
				  "severity_number": 13,
				  "body": "cart total mismatch for order 42",
				  "attributes": {
				    "sentry.environment": {"value": "prod", "type": "string"},
				    "sentry.release": {"value": "shop-frontend@1.4.2", "type": "string"},
				    "sentry.trace.parent_span_id": {"value": "b0e6f15b45c36285", "type": "string"},
				    "cart.size": {"value": 3, "type": "integer"},
				    "cart.gift": {"value": true, "type": "boolean"}
				  }
				}]}""");

		assertThat(records).hasSize(1);
		ProcessedLog record = records.get(0);
		assertThat(record.environment()).isEqualTo("prod");
		assertThat(record.release()).isEqualTo("shop-frontend@1.4.2");
		assertThat(record.traceId()).isEqualTo("5b8efff798038103d269b633813fc60c");
		assertThat(record.spanId()).isEqualTo("b0e6f15b45c36285");
		assertThat(record.level()).isEqualTo("warn");
		assertThat(record.severityNumber()).isEqualTo(13);
		assertThat(record.body()).isEqualTo("cart total mismatch for order 42");
		assertThat(record.timestamp()).isEqualTo(Instant.ofEpochMilli(1783166399500L));
		// Typed attribute objects are flattened to their plain values.
		assertThat(record.attributes().get("cart.size").asInt()).isEqualTo(3);
		assertThat(record.attributes().get("cart.gift").asBoolean()).isTrue();
		assertThat(record.attributes().get("sentry.environment").asText()).isEqualTo("prod");
	}

	@Test
	void defaultsMissingFields() {
		List<ProcessedLog> records = process("""
				{"items":[{"timestamp": 1783166399, "body": "plain"}]}""");

		assertThat(records).hasSize(1);
		ProcessedLog record = records.get(0);
		assertThat(record.environment()).isEqualTo("production");
		assertThat(record.release()).isNull();
		assertThat(record.traceId()).isNull();
		assertThat(record.spanId()).isNull();
		assertThat(record.level()).isEqualTo("info");
		assertThat(record.severityNumber()).isNull();
		assertThat(record.attributes().size()).isZero();
	}

	@Test
	void clampsSkewedTimestampsToReceivedAt() {
		List<ProcessedLog> records = process("""
				{"items":[
				  {"timestamp": 100.0, "body": "from 1970"},
				  {"body": "no timestamp at all"}
				]}""");

		assertThat(records).hasSize(2);
		assertThat(records).allSatisfy(record -> assertThat(record.timestamp()).isEqualTo(receivedAt));
	}

	@Test
	void skipsNonObjectRecordsAndToleratesMissingItems() {
		assertThat(process("""
				{"items":[42, "nope", {"timestamp": 1783166399, "body": "kept"}]}""")).hasSize(1);
		assertThat(process("{}")).isEmpty();
		assertThat(process("{\"items\": \"not an array\"}")).isEmpty();
	}

	private List<ProcessedLog> process(String payload) {
		return pipeline.process(new IngestItem.LogBatch(1L, receivedAt, mapper.readTree(payload)));
	}
}
