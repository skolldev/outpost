package dev.outpost.symbolication;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import dev.outpost.artifacts.BundleAssembledEvent;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Background re-symbolication (§6.2): after a bundle is assembled, events of
 * the matching release still flagged missing/partial are re-run from their
 * raw payload and updated in place. Grouping is intentionally not revisited —
 * the event keeps its issue; only the stored frames and status change.
 */
@Component
public class ResymbolicationJob {

	private static final int BATCH = 100;

	private static final Logger log = LoggerFactory.getLogger(ResymbolicationJob.class);

	private final JdbcClient jdbc;
	private final ObjectMapper mapper;
	private final Symbolicator symbolicator;
	private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "resymbolication");
		thread.setDaemon(true);
		return thread;
	});

	public ResymbolicationJob(JdbcClient jdbc, ObjectMapper mapper, Symbolicator symbolicator) {
		this.jdbc = jdbc;
		this.mapper = mapper;
		this.symbolicator = symbolicator;
	}

	@TransactionalEventListener
	public void onBundleAssembled(BundleAssembledEvent event) {
		if (event.release() == null || event.release().isBlank()) {
			return;
		}
		executor.submit(() -> {
			try {
				run(event.release());
			}
			catch (RuntimeException e) {
				log.error("re-symbolication for release {} failed", event.release(), e);
			}
		});
	}

	void run(String release) {
		int updated = 0;
		// Keyset pagination so each flagged event is visited exactly once per
		// run, even when its status stays flagged (still-missing maps).
		Timestamp afterTimestamp = new Timestamp(0);
		UUID afterId = new UUID(0, 0);
		while (true) {
			List<FlaggedEvent> batch = jdbc.sql("""
					SELECT id, project_id, "timestamp", raw, data->'_outpost_attachments' AS attachments
					FROM event
					WHERE release = ? AND symbolication_status IN ('missing_sourcemap', 'partial') AND raw IS NOT NULL
					  AND ("timestamp", id) > (?, ?)
					ORDER BY "timestamp", id
					LIMIT ?
					""")
				.param(release)
				.param(afterTimestamp)
				.param(afterId)
				.param(BATCH)
				.query((rs, i) -> new FlaggedEvent(rs.getObject("id", UUID.class), rs.getLong("project_id"),
						rs.getTimestamp("timestamp"), rs.getBytes("raw"), rs.getString("attachments")))
				.list();
			for (FlaggedEvent flagged : batch) {
				updated += resymbolicate(flagged) ? 1 : 0;
			}
			if (batch.size() < BATCH) {
				break;
			}
			FlaggedEvent last = batch.get(batch.size() - 1);
			afterTimestamp = last.timestamp();
			afterId = last.id();
		}
		if (updated > 0) {
			log.info("re-symbolicated {} events of release {}", updated, release);
		}
	}

	private boolean resymbolicate(FlaggedEvent flagged) {
		try {
			ObjectNode event = (ObjectNode) mapper.readTree(gunzip(flagged.rawGzip()));
			String status = symbolicator.symbolicate(event, flagged.projectId());
			if (status.equals(Symbolicator.STATUS_MISSING_SOURCEMAP)) {
				return false;
			}
			if (flagged.attachmentsJson() != null) {
				event.set("_outpost_attachments", mapper.readTree(flagged.attachmentsJson()));
			}
			jdbc.sql("""
					UPDATE event SET data = ?::jsonb, symbolication_status = ?
					WHERE id = ? AND "timestamp" = ?
					""")
				.param(mapper.writeValueAsString(event))
				.param(status)
				.param(flagged.id())
				.param(flagged.timestamp())
				.update();
			return true;
		}
		catch (RuntimeException e) {
			log.warn("re-symbolication of event {} failed: {}", flagged.id(), e.toString());
			return false;
		}
	}

	private record FlaggedEvent(UUID id, long projectId, Timestamp timestamp, byte[] rawGzip, String attachmentsJson) {
	}

	private static byte[] gunzip(byte[] gzipped) {
		try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gzipped))) {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			in.transferTo(out);
			return out.toByteArray();
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
