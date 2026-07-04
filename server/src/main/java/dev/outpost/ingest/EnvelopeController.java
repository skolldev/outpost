package dev.outpost.ingest;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
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
 * The Sentry-compatible telemetry ingest endpoint (§4.2). Parses + buffers,
 * responds immediately; processing is async in the ingest workers. Unknown item
 * types are skipped silently — that is the compatibility contract that keeps
 * newer SDKs from breaking ingest.
 */
@RestController
@RequestMapping("/api/{projectId:\\d+}")
public class EnvelopeController {

	private static final Logger log = LoggerFactory.getLogger(EnvelopeController.class);
	private static final int RETRY_AFTER_SECONDS = 30;

	private final EnvelopeParser parser;
	private final IngestAuthenticator authenticator;
	private final IngestQueue queue;
	private final ClientReportCounters clientReports;
	private final ObjectMapper mapper;

	public EnvelopeController(EnvelopeParser parser, IngestAuthenticator authenticator, IngestQueue queue,
			ClientReportCounters clientReports, ObjectMapper mapper) {
		this.parser = parser;
		this.authenticator = authenticator;
		this.queue = queue;
		this.clientReports = clientReports;
		this.mapper = mapper;
	}

	@PostMapping(path = "/envelope/", consumes = { "application/x-sentry-envelope", "text/plain", "*/*" })
	public ResponseEntity<Map<String, String>> envelope(@PathVariable long projectId,
			@RequestParam(name = "sentry_key", required = false) String sentryKeyParam, HttpServletRequest request)
			throws IOException {
		byte[] body = readBody(request);
		Envelope envelope = parser.parse(body);

		String key = authenticator.extractKey(request.getHeader("X-Sentry-Auth"), sentryKeyParam, envelope.header());
		if (!authenticator.isValidKey(projectId, key)) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("detail", "invalid or inactive DSN key"));
		}

		Instant receivedAt = Instant.now();
		List<IngestItem.Attachment> attachments = new ArrayList<>();
		List<JsonNode> events = new ArrayList<>();
		List<JsonNode> logBatches = new ArrayList<>();
		for (Envelope.Item item : envelope.items()) {
			switch (item.type()) {
				case "event" -> {
					JsonNode event = parseItemJson(item);
					if (event != null) {
						events.add(event);
					}
				}
				case "log" -> {
					JsonNode batch = parseItemJson(item);
					if (batch != null) {
						logBatches.add(batch);
					}
				}
				case "attachment" -> attachments.add(new IngestItem.Attachment(
						item.header().path("filename").asText("attachment"),
						item.header().path("content_type").asText("application/octet-stream"), item.payload()));
				case "client_report" -> {
					JsonNode report = parseItemJson(item);
					if (report != null) {
						clientReports.record(projectId, report);
					}
				}
				// session, sessions, check_in, profile, replay_*, statsd, metric_buckets,
				// transaction/span (until their phases) and anything unknown: skip silently.
				default -> {
				}
			}
		}

		List<IngestItem> queued = new ArrayList<>();
		for (JsonNode event : events) {
			// Attachments in an envelope belong to its (single) event; multi-event
			// envelopes don't occur in practice, so attach to each parsed event.
			queued.add(new IngestItem.ErrorEvent(projectId, receivedAt, event, attachments));
		}
		for (JsonNode batch : logBatches) {
			queued.add(new IngestItem.LogBatch(projectId, receivedAt, batch));
		}
		for (IngestItem item : queued) {
			if (!queue.offer(item)) {
				return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
					.header("Retry-After", String.valueOf(RETRY_AFTER_SECONDS))
					.header("X-Sentry-Rate-Limits", RETRY_AFTER_SECONDS + ":all:organization")
					.body(Map.of("detail", "ingest buffer full"));
			}
		}

		return ResponseEntity.ok(Map.of("id", eventId(envelope, events)));
	}

	@PostMapping({ "/security/", "/minidump/" })
	public ResponseEntity<Void> notImplemented() {
		return ResponseEntity.notFound().build();
	}

	private byte[] readBody(HttpServletRequest request) throws IOException {
		InputStream in = request.getInputStream();
		if ("gzip".equalsIgnoreCase(request.getHeader("Content-Encoding"))) {
			try {
				in = new GZIPInputStream(in);
			}
			catch (ZipException e) {
				throw new EnvelopeParser.MalformedEnvelopeException("invalid gzip body");
			}
		}
		byte[] body = in.readNBytes(EnvelopeParser.MAX_ENVELOPE_BYTES + 1);
		if (body.length > EnvelopeParser.MAX_ENVELOPE_BYTES) {
			throw new EnvelopeParser.OversizeException("envelope exceeds size limit");
		}
		return body;
	}

	private JsonNode parseItemJson(Envelope.Item item) {
		try {
			JsonNode node = mapper.readTree(item.payload());
			return node != null && node.isObject() ? node : null;
		}
		catch (tools.jackson.core.JacksonException e) {
			log.debug("dropping item with malformed JSON payload: {}", e.getMessage());
			return null;
		}
	}

	private String eventId(Envelope envelope, List<JsonNode> events) {
		if (envelope.header().hasNonNull("event_id")) {
			return envelope.header().get("event_id").asText();
		}
		for (JsonNode event : events) {
			if (event.hasNonNull("event_id")) {
				return event.get("event_id").asText();
			}
		}
		return UUID.randomUUID().toString().replace("-", "");
	}

	@ExceptionHandler(EnvelopeParser.MalformedEnvelopeException.class)
	public ResponseEntity<Map<String, String>> malformed(EnvelopeParser.MalformedEnvelopeException e) {
		return ResponseEntity.badRequest().body(Map.of("detail", e.getMessage()));
	}

	@ExceptionHandler(EnvelopeParser.OversizeException.class)
	public ResponseEntity<Map<String, String>> oversize(EnvelopeParser.OversizeException e) {
		return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of("detail", e.getMessage()));
	}
}
