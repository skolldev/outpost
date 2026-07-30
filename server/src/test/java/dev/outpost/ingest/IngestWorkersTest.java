package dev.outpost.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.outpost.pipeline.ErrorPipeline;
import dev.outpost.pipeline.EventStore;
import dev.outpost.pipeline.LogPipeline;
import dev.outpost.pipeline.LogStore;
import dev.outpost.pipeline.ProcessedEvent;
import dev.outpost.pipeline.TransactionPipeline;
import dev.outpost.pipeline.TransactionStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tools.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class IngestWorkersTest {

	private static final byte[] EVENT_ENVELOPE = """
			{}
			{"type":"event"}
			{"event_id":"0123456789abcdef0123456789abcdef"}
			""".getBytes(StandardCharsets.UTF_8);

	private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
	private final IngestMetrics metrics = new IngestMetrics(registry);
	private final IngestQueue queue = new IngestQueue(10, metrics);
	private final EnvelopeSpool spool = mock(EnvelopeSpool.class);
	private final ErrorPipeline pipeline = mock(ErrorPipeline.class);
	private final EventStore store = mock(EventStore.class);
	private final SpoolFile spoolFile = new SpoolFile(Path.of("envelope.spool"), false);

	@Test
	void parseFailureStillRemovesSpoolFile() throws IOException, InterruptedException {
		EnvelopeParser parser = mock(EnvelopeParser.class);
		when(spool.open(spoolFile)).thenThrow(new IOException("read failed"));
		QueuedEnvelope envelope = enqueue();

		workers(parser).digest(queue.nextBatch(10, 0));

		verify(spool).delete(envelope.spoolFile());
		assertThat(queue.outstanding()).isZero();
	}

	@Test
	void storeFailureStillRemovesSpoolFileAndRecordsTheDrop() throws IOException, InterruptedException {
		EnvelopeParser parser = new EnvelopeParser(new ObjectMapper());
		when(spool.open(spoolFile)).thenAnswer(invocation -> new ByteArrayInputStream(EVENT_ENVELOPE));
		when(pipeline.process(any(IngestItem.ErrorEvent.class))).thenReturn(mock(ProcessedEvent.class));
		doThrow(new IllegalStateException("database unavailable"))
			.when(store)
			.store(any());
		QueuedEnvelope envelope = enqueue();

		workers(parser).digest(queue.nextBatch(10, 0));

		verify(spool).delete(envelope.spoolFile());
		assertThat(queue.outstanding()).isZero();
		assertThat(registry.get("outpost.ingest.dropped").tag("stage", "store").counter().count()).isEqualTo(1);
	}

	private QueuedEnvelope enqueue() {
		QueuedEnvelope envelope = new QueuedEnvelope(42, Instant.now(), spoolFile);
		assertThat(queue.offer(envelope)).isTrue();
		return envelope;
	}

	private IngestWorkers workers(EnvelopeParser parser) {
		return new IngestWorkers(queue, spool, parser, mock(ClientReportCounters.class), pipeline, store,
				mock(LogPipeline.class), mock(LogStore.class), mock(TransactionPipeline.class),
				mock(TransactionStore.class), metrics, 0, 10, 0, Duration.ZERO);
	}
}
