package dev.outpost.notifications;

import dev.outpost.config.OutpostProperties;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The one deep module behind the publisher seam (parent #41). Owns everything
 * existing code must not know about: channel matching (trigger + Project filter
 * + Environment filter), history persistence, per-type payload formatting, and
 * asynchronous HTTP delivery with retries. Callers see only
 * {@link NotificationPublisher#publish}.
 *
 * <p>Delivery is fully decoupled from ingest and uptime (ADR 0005):
 * {@code publish} hands the occurrence to a virtual-thread executor and returns.
 * A slow or dead receiver, or a saturated executor, can never backpressure the
 * caller. Each matched channel gets a {@code pending} history row up front,
 * moved to {@code sent}/{@code failed} once the send resolves; a shutdown
 * mid-send leaves the row stale rather than redelivering.
 *
 * <p>This slice delivers {@code new_issue} to {@code generic_json} channels.
 * Teams formatting (#46) and other triggers (#44, #45) slot in behind the same
 * seam without touching callers.
 */
@Component
public class NotificationService implements NotificationPublisher, SmartLifecycle {

	private record MatchedChannel(long id, String url) {
	}

	private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

	private final JdbcClient jdbc;
	private final GenericJsonFormatter genericJsonFormatter;
	private final WebhookSender sender;
	private final OutpostProperties properties;

	// Recreated on each start() so a SmartLifecycle stop()/start() cycle (e.g.
	// the test-context pause) gets a live executor.
	private ExecutorService deliveries;
	private volatile boolean running;

	public NotificationService(JdbcClient jdbc, GenericJsonFormatter genericJsonFormatter, WebhookSender sender,
			OutpostProperties properties) {
		this.jdbc = jdbc;
		this.genericJsonFormatter = genericJsonFormatter;
		this.sender = sender;
		this.properties = properties;
	}

	@Override
	public synchronized void start() {
		if (running) {
			return;
		}
		running = true;
		deliveries = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("notify-", 0).factory());
	}

	@Override
	public synchronized void stop() {
		running = false;
		if (deliveries != null) {
			// Fire-and-forget like uptime probes: don't await in-flight sends;
			// a row left pending by shutdown is best-effort-acceptable (ADR 0005).
			deliveries.shutdownNow();
		}
	}

	@Override
	public boolean isRunning() {
		return running;
	}

	@Override
	public void publish(NotificationOccurrence occurrence) {
		ExecutorService executor = this.deliveries;
		if (!running || executor == null) {
			return;
		}
		try {
			executor.submit(() -> deliverSafely(occurrence));
		}
		catch (RejectedExecutionException e) {
			// Executor shutting down: best-effort means we drop it, never block ingest.
			log.debug("dropping notification for {}: executor unavailable", occurrence.triggerType());
		}
	}

	private void deliverSafely(NotificationOccurrence occurrence) {
		try {
			deliver(occurrence);
		}
		catch (RuntimeException e) {
			// Delivery must never escalate; the seam is fire-and-forget.
			log.warn("notification delivery for {} failed unexpectedly: {}", occurrence.triggerType(), e.toString());
		}
	}

	private void deliver(NotificationOccurrence occurrence) {
		switch (occurrence) {
			case NotificationOccurrence.NewIssue issue -> deliverNewIssue(issue);
		}
	}

	private void deliverNewIssue(NotificationOccurrence.NewIssue occurrence) {
		NotificationContext context = resolveContext(occurrence);
		if (context == null) {
			return; // Project vanished between insert and delivery; nothing to announce.
		}
		List<MatchedChannel> matches = matchChannels(occurrence);
		if (matches.isEmpty()) {
			return;
		}
		// Format once: the payload is identical for every matched channel (only the
		// destination URL differs). Do it before writing any pending row so a
		// formatting failure can't strand a row stuck at 'pending'.
		String payload = genericJsonFormatter.format(occurrence, context);
		String summary = occurrence.triggerType() + ": " + occurrence.title();
		for (MatchedChannel channel : matches) {
			long historyId = insertPending(channel.id(), occurrence.triggerType(), summary);
			WebhookSender.Result result = sender.send(channel.url(), payload);
			recordOutcome(historyId, result);
		}
	}

	/** Resolves Project display fields and the deep link, or null if the Project is gone. */
	private NotificationContext resolveContext(NotificationOccurrence.NewIssue occurrence) {
		return jdbc.sql("SELECT slug, name FROM project WHERE id = ?")
			.param(occurrence.projectId())
			.query((rs, i) -> new NotificationContext(rs.getString("name"), rs.getString("slug"),
					issueLink(occurrence.issueId())))
			.optional()
			.orElse(null);
	}

	/**
	 * Channels that fire on this occurrence: enabled, Generic JSON, subscribed to
	 * the trigger, and passing both filters. An empty {@code project_filter}
	 * matches all Projects; an empty {@code environment_filter} matches all
	 * Environments. A non-empty {@code environment_filter} never matches an
	 * occurrence with no environment — {@code null = ANY(...)} is not true, so
	 * only the empty-filter branch admits it, exactly the spec semantics.
	 */
	private List<MatchedChannel> matchChannels(NotificationOccurrence.NewIssue occurrence) {
		return jdbc.sql("""
				SELECT id, url FROM notification_channel
				WHERE enabled = true
				  AND type = 'generic_json'
				  AND ? = ANY(triggers)
				  AND (cardinality(project_filter) = 0 OR ? = ANY(project_filter))
				  AND (cardinality(environment_filter) = 0 OR ? = ANY(environment_filter))
				""")
			.param(occurrence.triggerType())
			.param(occurrence.projectId())
			.param(occurrence.environment())
			.query((rs, i) -> new MatchedChannel(rs.getLong("id"), rs.getString("url")))
			.list();
	}

	private long insertPending(long channelId, String triggerType, String summary) {
		return jdbc.sql("""
				INSERT INTO notification_history (channel_id, trigger_type, status, summary)
				VALUES (?, ?, 'pending', ?)
				RETURNING id
				""").param(channelId).param(triggerType).param(summary).query(Long.class).single();
	}

	private void recordOutcome(long historyId, WebhookSender.Result result) {
		jdbc.sql("""
				UPDATE notification_history
				SET status = ?, error_detail = ?, updated_at = now()
				WHERE id = ?
				""")
			.param(result.success() ? "sent" : "failed")
			.param(result.errorDetail())
			.param(historyId)
			.update();
	}

	/**
	 * Deep link to an Issue, built from {@code outpost.public-url} like the DSN in
	 * {@code ProjectController}: a path prefix (reverse-proxy sub-path) is
	 * preserved so the link resolves through the same base the UI is served under.
	 */
	private String issueLink(long issueId) {
		return properties.baseUrl() + "/issues/" + issueId;
	}
}
