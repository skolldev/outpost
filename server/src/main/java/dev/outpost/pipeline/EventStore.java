package dev.outpost.pipeline;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import dev.outpost.db.PartitionManager;
import dev.outpost.ingest.IngestMetrics;
import dev.outpost.notifications.NotificationOccurrence;
import dev.outpost.notifications.NotificationPublisher;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Persists processed error events: environment/release auto-upsert,
 * issue upsert with regression handling, per-environment stats, then a JDBC
 * batch insert of the event rows.
 *
 * <p>Crosses the publisher seam (#41) when an issue upsert inserts — a
 * fingerprint's first Event — but only after the storing transaction commits, so
 * a notification is never sent for an Issue that rolled back. Repeats and
 * regressions do not publish.
 *
 * <p>Redelivered events are dropped before anything is written (#95): an SDK
 * that retries a failed request sends the same {@code event_id} again, and the
 * aggregates must count what was stored, not what arrived.
 */
@Component
public class EventStore {

	private static final Logger log = LoggerFactory.getLogger(EventStore.class);

	private record IssueUpsert(long id, boolean inserted) {
	}

	/** An event's storage identity: the {@code event} primary key. */
	private record EventKey(UUID id, Instant timestamp) {
	}

	// xmax = 0 marks a row this statement just inserted; a row updated via ON
	// CONFLICT has a non-zero xmax. This distinguishes a fingerprint's first
	// Event (a new Issue → notify) from a repeat or a regression (never notify).
	private static final String ISSUE_UPSERT = """
			INSERT INTO issue (project_id, fingerprint, title, culprit, level, status, first_seen, last_seen, event_count)
			VALUES (?, ?, ?, ?, ?, 'unresolved', ?, ?, 1)
			ON CONFLICT (project_id, fingerprint) DO UPDATE SET
			    first_seen = LEAST(issue.first_seen, EXCLUDED.first_seen),
			    last_seen = GREATEST(issue.last_seen, EXCLUDED.last_seen),
			    title = EXCLUDED.title,
			    culprit = EXCLUDED.culprit,
			    level = EXCLUDED.level,
			    event_count = issue.event_count + 1,
			    status = CASE WHEN issue.status = 'resolved' THEN 'unresolved' ELSE issue.status END
			RETURNING id, (xmax = 0) AS inserted
			""";

	private static final String EVENT_INSERT = """
			INSERT INTO event (id, project_id, issue_id, environment, release, "timestamp", trace_id, level,
			                   message, exception_type, user_ident, data, raw, symbolication_status)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
			ON CONFLICT DO NOTHING
			""";

	// Which of the batch's events are already stored, probed on exactly the key
	// EVENT_INSERT conflicts on — so "found here" and "would not insert there" are
	// the same question, and each probe prunes to the one partition it can be in.
	private static final String STORED_EVENTS = """
			SELECT e.id, e."timestamp"
			FROM event e
			JOIN unnest(string_to_array(?, ',')::uuid[], string_to_array(?, ',')::timestamptz[]) AS probe (id, ts)
			  ON e.id = probe.id AND e."timestamp" = probe.ts
			""";

	private final JdbcTemplate jdbc;
	private final TransactionTemplate transaction;
	private final PartitionManager partitions;
	private final ObjectMapper mapper;
	private final EventIssueLock eventIssueLock;
	private final NotificationPublisher notifications;
	private final IngestMetrics metrics;
	private final TelemetryOrigins origins;

	public EventStore(JdbcTemplate jdbc, PlatformTransactionManager transactionManager, PartitionManager partitions,
			ObjectMapper mapper, EventIssueLock eventIssueLock, NotificationPublisher notifications,
			IngestMetrics metrics, TelemetryOrigins origins) {
		this.jdbc = jdbc;
		this.transaction = new TransactionTemplate(transactionManager);
		this.partitions = partitions;
		this.mapper = mapper;
		this.eventIssueLock = eventIssueLock;
		this.notifications = notifications;
		this.metrics = metrics;
		this.origins = origins;
	}

	/**
	 * Stores each project's events in a separate transaction so unrelated projects
	 * can ingest concurrently. On failure, falls back to storing events one by one
	 * so a poison event cannot sink its project batch.
	 */
	public void store(List<ProcessedEvent> batch) {
		if (batch.isEmpty()) {
			return;
		}
		// Partition DDL runs in its own transaction, before the insert transaction.
		partitions.ensurePartitions(PartitionManager.EVENT, batch.stream().map(ProcessedEvent::timestamp).toList());
		Map<Long, List<ProcessedEvent>> byProject = batch.stream()
			.collect(Collectors.groupingBy(ProcessedEvent::projectId, LinkedHashMap::new, Collectors.toList()));
		byProject.values().forEach(this::storeProject);
	}

	private void storeProject(List<ProcessedEvent> batch) {
		PoisonIsolation.run(log, batch, events -> {
			List<NotificationOccurrence> newIssues = new ArrayList<>();
			transaction.executeWithoutResult(status -> storeAll(events, newIssues));
			// Published after commit, so a rolled-back Issue never notifies — which
			// is also why this sits inside the attempt: a failed try leaves its
			// half-filled list behind with it. The seam is fire-and-forget (ADR
			// 0005) and never throws, but guard anyway so a notification hiccup
			// can't fail an already-stored batch.
			for (NotificationOccurrence occurrence : newIssues) {
				try {
					notifications.publish(occurrence);
				}
				catch (RuntimeException e) {
					log.warn("failed to publish new-issue notification: {}", e.toString());
				}
			}
		}, (event, failure) -> {
			metrics.dropped(IngestMetrics.DropStage.STORE);
			log.warn("dropping unstorable event {}: {}", event.id(), failure.toString());
		});
	}

	private void storeAll(List<ProcessedEvent> unfiltered, List<NotificationOccurrence> newIssues) {
		eventIssueLock.acquire(unfiltered.getFirst().projectId());
		// Under the lock, so no concurrent writer can land an event between this
		// lookup and the insert below: what is missing here does insert there.
		List<ProcessedEvent> batch = firstDeliveries(unfiltered);
		if (batch.isEmpty()) {
			return;
		}
		origins.ensure(batch);
		List<Object[]> eventRows = batch.stream().map(event -> {
			IssueUpsert upsert = jdbc.queryForObject(ISSUE_UPSERT,
					(rs, i) -> new IssueUpsert(rs.getLong("id"), rs.getBoolean("inserted")), event.projectId(),
					event.fingerprint(), event.title(), event.culprit(), event.level(),
					Timestamp.from(event.timestamp()), Timestamp.from(event.timestamp()));
			long issueId = upsert.id();
			if (upsert.inserted()) {
				newIssues.add(new NotificationOccurrence.NewIssue(event.projectId(), issueId, event.title(),
						event.culprit(), event.environment(), event.timestamp()));
			}
			jdbc.update("""
					INSERT INTO issue_env_stats (issue_id, environment, event_count, last_seen)
					VALUES (?, ?, 1, ?)
					ON CONFLICT (issue_id, environment) DO UPDATE SET
					    event_count = issue_env_stats.event_count + 1,
					    last_seen = GREATEST(issue_env_stats.last_seen, EXCLUDED.last_seen)
					""", issueId, event.environment(), Timestamp.from(event.timestamp()));
			return new Object[] { event.id(), event.projectId(), issueId, event.environment(), event.release(),
					Timestamp.from(event.timestamp()), event.traceId(), event.level(), event.message(),
					event.exceptionType(), event.userIdent(), json(event), event.rawGzip(),
					event.symbolicationStatus() };
		}).toList();

		jdbc.batchUpdate(EVENT_INSERT, eventRows);
	}

	/**
	 * The batch minus every event already stored, and minus repeats within the
	 * batch itself. Everything downstream — the issue counter, the per-environment
	 * stats, the regression flip — keys off this list, so an aggregate can only
	 * move for an event that actually inserts.
	 *
	 * <p>A redelivery is absorbed rather than raised: by the time a worker stores,
	 * the SDK has long had its 200 and there is no caller left to fail. The
	 * duplicate counter is where it shows up.
	 */
	private List<ProcessedEvent> firstDeliveries(List<ProcessedEvent> batch) {
		Set<EventKey> seen = storedKeys(batch);
		List<ProcessedEvent> fresh = new ArrayList<>(batch.size());
		for (ProcessedEvent event : batch) {
			if (seen.add(new EventKey(event.id(), event.timestamp()))) {
				fresh.add(event);
			}
		}
		metrics.duplicates(batch.size() - fresh.size());
		return fresh;
	}

	private Set<EventKey> storedKeys(List<ProcessedEvent> batch) {
		String ids = batch.stream().map(event -> event.id().toString()).collect(Collectors.joining(","));
		String timestamps = batch.stream().map(event -> event.timestamp().toString()).collect(Collectors.joining(","));
		return new HashSet<>(jdbc.query(STORED_EVENTS,
				(rs, i) -> new EventKey(rs.getObject("id", UUID.class), rs.getTimestamp("timestamp").toInstant()), ids,
				timestamps));
	}

	private String json(ProcessedEvent event) {
		try {
			return mapper.writeValueAsString(event.data());
		}
		catch (JacksonException e) {
			throw new IllegalStateException("event data not serializable", e);
		}
	}
}
