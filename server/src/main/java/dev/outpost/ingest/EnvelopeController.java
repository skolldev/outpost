package dev.outpost.ingest;

import dev.outpost.config.OutpostProperties;
import tools.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Sentry-compatible telemetry ingest endpoint. It validates and spools each
 * envelope, then responds as soon as the fixed-size spool reference is queued.
 */
@RestController
@RequestMapping("/api/{projectId:\\d+}")
public class EnvelopeController {

	private static final Logger log = LoggerFactory.getLogger(EnvelopeController.class);
	private static final int RETRY_AFTER_SECONDS = 30;

	// Spring Security's default value plus no-transform. SDKs send these acks with
	// fetch(keepalive), never reading the body, so a fronting proxy that gzips the
	// tiny JSON (it is a few dozen bytes) and then buffers the compressed stream
	// leaves the browser's request pending until the connection is torn down —
	// seconds later. no-transform tells the proxy to pass the ack through
	// untransformed so the stream closes immediately. Same hazard the log-tail SSE
	// avoids by staying out of the compression mime-types (application.yaml).
	private static final String INGEST_CACHE_CONTROL = "no-cache, no-store, max-age=0, must-revalidate, no-transform";

	private final EnvelopeParser parser;
	private final EnvelopeSpool spool;
	private final IngestAuthenticator authenticator;
	private final IngestQueue queue;
	private final ClientReportCounters clientReports;
	private final IngestMetrics metrics;
	private final OutpostProperties properties;

	public EnvelopeController(EnvelopeParser parser, EnvelopeSpool spool, IngestAuthenticator authenticator,
			IngestQueue queue, ClientReportCounters clientReports, IngestMetrics metrics,
			OutpostProperties properties) {
		this.parser = parser;
		this.spool = spool;
		this.authenticator = authenticator;
		this.queue = queue;
		this.clientReports = clientReports;
		this.metrics = metrics;
		this.properties = properties;
	}

	@PostMapping(path = "/envelope/", consumes = { "application/x-sentry-envelope", "text/plain", "*/*" })
	public ResponseEntity<Map<String, String>> envelope(@PathVariable long projectId,
			@RequestParam(name = "sentry_key", required = false) String sentryKeyParam, HttpServletRequest request,
			HttpServletResponse response) {
		// Set before any early return or thrown exception so every ack — accepted,
		// rate-limited, forbidden, malformed — carries it. Spring Security's
		// CacheControlHeadersWriter leaves Cache-Control alone once it is present.
		response.setHeader("Cache-Control", INGEST_CACHE_CONTROL);
		Instant receivedAt = Instant.now();
		SpoolFile spoolFile;
		try {
			spoolFile = spool.write(request);
		}
		catch (EnvelopeSpool.SpoolWriteException e) {
			log.error("failed to spool envelope for project {}", projectId, e.getCause());
			throw e;
		}

		boolean queued = false;
		try {
			Inspection inspection = inspect(spoolFile);
			String key = authenticator.extractKey(request.getHeader("X-Sentry-Auth"), sentryKeyParam,
					inspection.header());
			if (!authenticator.isValidKey(projectId, key)) {
				metrics.envelope(IngestMetrics.Outcome.FORBIDDEN);
				return ResponseEntity.status(HttpStatus.FORBIDDEN)
					.body(Map.of("detail", "invalid or inactive DSN key", "dsn", properties.dsn(key, projectId)));
			}

			if (inspection.itemCounts().isEmpty()) {
				if (inspection.hasClientReport()) {
					recordClientReports(projectId, spoolFile);
				}
				metrics.envelope(IngestMetrics.Outcome.ACCEPTED);
				return ResponseEntity.ok(Map.of("id", eventId(inspection)));
			}

			QueuedEnvelope envelope = new QueuedEnvelope(projectId, receivedAt, spoolFile);
			if (!queue.offer(envelope)) {
				if (inspection.hasClientReport()) {
					recordClientReports(projectId, spoolFile);
				}
				recordItems(inspection, false);
				metrics.envelope(IngestMetrics.Outcome.REJECTED);
				return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
					.header("Retry-After", String.valueOf(RETRY_AFTER_SECONDS))
					.header("X-Sentry-Rate-Limits", RETRY_AFTER_SECONDS + ":all:organization")
					.body(Map.of("detail", "ingest buffer full"));
			}
			queued = true;
			recordItems(inspection, true);
			metrics.envelope(IngestMetrics.Outcome.ACCEPTED);
			return ResponseEntity.ok(Map.of("id", eventId(inspection)));
		}
		catch (EOFException | ZipException e) {
			if (!spoolFile.gzip()) {
				log.error("failed to read spooled envelope for project {} from {}", projectId, spoolFile.path(), e);
				throw new EnvelopeSpool.SpoolReadException(e);
			}
			throw new EnvelopeParser.MalformedEnvelopeException("invalid gzip body");
		}
		catch (IOException e) {
			log.error("failed to read spooled envelope for project {} from {}", projectId, spoolFile.path(), e);
			throw new EnvelopeSpool.SpoolReadException(e);
		}
		finally {
			if (!queued) {
				spool.delete(spoolFile);
			}
		}
	}

	@PostMapping({ "/security/", "/minidump/" })
	public ResponseEntity<Void> notImplemented() {
		return ResponseEntity.notFound().build();
	}

	private Inspection inspect(SpoolFile spoolFile) throws IOException {
		InspectionBuilder inspection = new InspectionBuilder();
		JsonNode header;
		try (InputStream in = spool.open(spoolFile)) {
			header = parser.parse(in, inspection::accept);
		}
		return inspection.build(header);
	}

	private void recordClientReports(long projectId, SpoolFile spoolFile) throws IOException {
		try (InputStream in = spool.open(spoolFile)) {
			parser.parse(in, item -> {
				if (item.kind() != Envelope.ItemKind.CLIENT_REPORT) {
					return;
				}
				JsonNode report = parser.parseObjectPayload(item);
				if (report != null) {
					clientReports.record(projectId, report);
				}
			});
		}
	}

	private void recordItems(Inspection inspection, boolean accepted) {
		for (IngestMetrics.Signal signal : IngestMetrics.Signal.values()) {
			for (int i = 0; i < inspection.itemCounts().getOrDefault(signal, 0); i++) {
				if (accepted) {
					metrics.itemQueued(signal);
				}
				else {
					metrics.itemRejected(signal);
				}
			}
		}
	}

	private String eventId(Inspection inspection) {
		if (inspection.header().hasNonNull("event_id")) {
			return inspection.header().get("event_id").asText();
		}
		if (inspection.itemEventId() != null) {
			return inspection.itemEventId();
		}
		return UUID.randomUUID().toString().replace("-", "");
	}

	@ExceptionHandler(EnvelopeParser.MalformedEnvelopeException.class)
	public ResponseEntity<Map<String, String>> malformed(EnvelopeParser.MalformedEnvelopeException e) {
		metrics.envelope(IngestMetrics.Outcome.MALFORMED);
		return ResponseEntity.badRequest().body(Map.of("detail", e.getMessage()));
	}

	@ExceptionHandler(EnvelopeParser.OversizeException.class)
	public ResponseEntity<Map<String, String>> oversize(EnvelopeParser.OversizeException e) {
		metrics.envelope(IngestMetrics.Outcome.OVERSIZE);
		return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).body(Map.of("detail", e.getMessage()));
	}

	@ExceptionHandler(EnvelopeSpool.SpoolWriteException.class)
	public ResponseEntity<Map<String, String>> spoolWriteFailure() {
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
			.body(Map.of("detail", "unable to spool envelope"));
	}

	@ExceptionHandler(EnvelopeSpool.SpoolReadException.class)
	public ResponseEntity<Map<String, String>> spoolReadFailure() {
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
			.body(Map.of("detail", "unable to read spooled envelope"));
	}

	private class InspectionBuilder {

		private final EnumMap<IngestMetrics.Signal, Integer> itemCounts =
				new EnumMap<>(IngestMetrics.Signal.class);
		private String itemEventId;
		private boolean hasClientReport;

		void accept(Envelope.Item item) {
			if (item.kind() == Envelope.ItemKind.CLIENT_REPORT) {
				hasClientReport = true;
				return;
			}
			Envelope.SignalItem signalItem = parser.signalItem(item);
			if (signalItem == null) {
				return;
			}
			itemCounts.merge(signalItem.signal(), 1, Integer::sum);
			JsonNode payload = signalItem.payload();
			if (signalItem.signal() == IngestMetrics.Signal.ERROR && itemEventId == null
					&& payload.hasNonNull("event_id")) {
				itemEventId = payload.get("event_id").asText();
			}
		}

		Inspection build(JsonNode header) {
			return new Inspection(header, itemEventId, Map.copyOf(itemCounts), hasClientReport);
		}
	}

	private record Inspection(JsonNode header, String itemEventId,
			Map<IngestMetrics.Signal, Integer> itemCounts, boolean hasClientReport) {
	}
}
