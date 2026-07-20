package dev.outpost.notifications;

import dev.outpost.config.OutpostProperties;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
 * <p>This module delivers {@code new_issue}, {@code incident_started}, and
 * {@code incident_resolved} to every matching channel and
 * backs the Admin test-send action (#44) via {@link #testSend}: a {@code test}
 * occurrence bypasses matching (the channel is named directly) but runs the same
 * format → history → async-deliver pipeline, so a green test proves the whole
 * path. Per-type formatting lives behind the {@link NotificationFormatter} seam
 * (resolved by {@link NotificationFormatters}): Generic JSON and Teams Adaptive
 * Card (#46) each slot in as a formatter bean without touching this delivery
 * path, the matching query, or any caller.
 */
@Component
public class NotificationService implements NotificationPublisher, SmartLifecycle {

	/**
	 * Outcome of an Admin test-send. {@code NOT_FOUND}/{@code DISABLED} are refusals
	 * the endpoint maps to 404/409; {@code SENT}/{@code FAILED} carry (on failure)
	 * the receiver error the UI shows inline. {@code UNAVAILABLE} means the delivery
	 * executor was stopped (shutdown).
	 */
	public record TestSendResult(Status status, String errorDetail) {

		public enum Status {
			NOT_FOUND, DISABLED, SENT, FAILED, UNAVAILABLE
		}

		static TestSendResult of(Status status) {
			return new TestSendResult(status, null);
		}
	}

	private record MatchedChannel(long id, String type, String url) {
	}

	/** A channel resolved for test-send: everything delivery and formatting need. */
	private record ChannelRow(long id, String name, String type, String url, boolean enabled) {
	}

	private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

	private final JdbcClient jdbc;
	private final NotificationFormatters formatters;
	private final WebhookSender sender;
	private final OutpostProperties properties;

	// Recreated on each start() so a SmartLifecycle stop()/start() cycle (e.g.
	// the test-context pause) gets a live executor.
	private ExecutorService deliveries;
	private volatile boolean running;

	public NotificationService(JdbcClient jdbc, NotificationFormatters formatters, WebhookSender sender,
			OutpostProperties properties) {
		this.jdbc = jdbc;
		this.formatters = formatters;
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
			case NotificationOccurrence.IncidentStarted incident ->
				deliverIncident(incident, incident.projectId(), incident.environment(), incident.monitorUrl());
			case NotificationOccurrence.IncidentResolved incident ->
				deliverIncident(incident, incident.projectId(), incident.environment(), incident.monitorUrl());
			case NotificationOccurrence.Test test -> deliverTest(test);
		}
	}

	private void deliverNewIssue(NotificationOccurrence.NewIssue occurrence) {
		NotificationContext context = resolveContext(occurrence.projectId(), issueLink(occurrence.issueId()));
		if (context == null) {
			return; // Project vanished between insert and delivery; nothing to announce.
		}
		deliverToMatches(occurrence, context, occurrence.projectId(), occurrence.environment(),
				occurrence.triggerType() + ": " + occurrence.title());
	}

	/**
	 * Both incident triggers deliver identically: a {@code /uptime} deep link, and a
	 * summary of the trigger type plus the probed monitor URL (a Monitor's identity —
	 * there is no name). The two variants differ only in the payload the formatter
	 * writes, so the delivery path is shared.
	 */
	private void deliverIncident(NotificationOccurrence occurrence, long projectId, String environment,
			String monitorUrl) {
		NotificationContext context = resolveContext(projectId, uptimeLink());
		if (context == null) {
			return; // Project vanished; the monitor and incident cascade with it.
		}
		deliverToMatches(occurrence, context, projectId, environment,
				occurrence.triggerType() + ": " + monitorUrl);
	}

	/**
	 * Match, format, and deliver an occurrence to every channel that fires on it.
	 * The payload depends only on the channel's <em>type</em> (the destination URL
	 * aside), so it is formatted once per distinct type and reused across channels
	 * of that type — and always before any pending row is written, so a formatting
	 * failure can't strand a row stuck at {@code pending}. Shared by every stored
	 * trigger type; {@code test} bypasses this (channel named directly).
	 */
	private void deliverToMatches(NotificationOccurrence occurrence, NotificationContext context, long projectId,
			String environment, String summary) {
		List<MatchedChannel> matches = matchChannels(occurrence.triggerType(), projectId, environment);
		if (matches.isEmpty()) {
			return;
		}
		Map<String, String> payloadByType = new HashMap<>();
		for (MatchedChannel channel : matches) {
			String payload = payloadByType.computeIfAbsent(channel.type(),
					type -> formatters.format(type, occurrence, context));
			long historyId = insertPending(channel.id(), occurrence.triggerType(), summary);
			WebhookSender.Result result = sender.send(channel.url(), payload);
			recordOutcome(historyId, result);
		}
	}

	/**
	 * Admin test-send (#44): deliver a {@code test} occurrence to one named channel,
	 * bypassing matching but respecting the {@code enabled} flag and per-type
	 * formatting. Runs on the same delivery executor as real notifications, and the
	 * caller ({@link #testSend}) awaits the outcome so the UI can report it inline.
	 *
	 * @return the delivered row's id and outcome, or {@code null} if the channel
	 * vanished or was disabled between the pre-check and here.
	 */
	private TestSendResult deliverTest(NotificationOccurrence.Test occurrence) {
		ChannelRow channel = loadChannel(occurrence.channelId());
		if (channel == null) {
			return TestSendResult.of(TestSendResult.Status.NOT_FOUND);
		}
		if (!channel.enabled()) {
			return TestSendResult.of(TestSendResult.Status.DISABLED);
		}
		String payload = formatters.format(channel.type(), occurrence,
				new NotificationContext(null, null, settingsLink()));
		String summary = "test: " + channel.name();
		long historyId = insertPending(channel.id(), occurrence.triggerType(), summary);
		WebhookSender.Result result = sender.send(channel.url(), payload);
		recordOutcome(historyId, result);
		return new TestSendResult(result.success() ? TestSendResult.Status.SENT : TestSendResult.Status.FAILED,
				result.errorDetail());
	}

	/**
	 * Fire a test-send at one channel and wait for the outcome. Refusals
	 * ({@code NOT_FOUND}/{@code DISABLED}) are decided up front so the endpoint can
	 * answer without touching the network; the actual delivery is submitted to the
	 * delivery executor (same path as real notifications) and its result awaited so
	 * the Admin sees the outcome inline.
	 */
	public TestSendResult testSend(long channelId) {
		ChannelRow channel = loadChannel(channelId);
		if (channel == null) {
			return TestSendResult.of(TestSendResult.Status.NOT_FOUND);
		}
		if (!channel.enabled()) {
			return TestSendResult.of(TestSendResult.Status.DISABLED);
		}
		ExecutorService executor = this.deliveries;
		if (!running || executor == null) {
			return TestSendResult.of(TestSendResult.Status.UNAVAILABLE);
		}
		NotificationOccurrence.Test occurrence = new NotificationOccurrence.Test(channel.id(), channel.name(),
				Instant.now());
		try {
			Future<TestSendResult> future = executor.submit(() -> deliverTest(occurrence));
			return future.get();
		}
		catch (RejectedExecutionException e) {
			return TestSendResult.of(TestSendResult.Status.UNAVAILABLE);
		}
		catch (ExecutionException e) {
			// deliverTest itself threw (e.g. formatting) — report as a failed send.
			log.warn("test-send to channel {} failed unexpectedly: {}", channelId, e.getCause().toString());
			return new TestSendResult(TestSendResult.Status.FAILED, e.getCause().toString());
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return TestSendResult.of(TestSendResult.Status.UNAVAILABLE);
		}
	}

	/** Loads the channel fields delivery and formatting need, or null if it is gone. */
	private ChannelRow loadChannel(long channelId) {
		return jdbc.sql("SELECT id, name, type, url, enabled FROM notification_channel WHERE id = ?")
			.param(channelId)
			.query((rs, i) -> new ChannelRow(rs.getLong("id"), rs.getString("name"), rs.getString("type"),
					rs.getString("url"), rs.getBoolean("enabled")))
			.optional()
			.orElse(null);
	}

	/**
	 * Resolves Project display fields and pairs them with the supplied deep link,
	 * or null if the Project is gone. Shared by every occurrence that names a
	 * Project; the caller supplies the link because it differs per trigger (Issue
	 * vs. Uptime).
	 */
	private NotificationContext resolveContext(long projectId, String link) {
		return jdbc.sql("SELECT slug, name FROM project WHERE id = ?")
			.param(projectId)
			.query((rs, i) -> new NotificationContext(rs.getString("name"), rs.getString("slug"), link))
			.optional()
			.orElse(null);
	}

	/**
	 * Channels that fire on this occurrence: enabled, subscribed to the trigger,
	 * and passing both filters — of any channel type (the {@link
	 * NotificationFormatter} seam formats per type at delivery). An empty
	 * {@code project_filter} matches all Projects; an empty
	 * {@code environment_filter} matches all Environments. A non-empty
	 * {@code environment_filter} never matches an occurrence with no environment —
	 * {@code null = ANY(...)} is not true, so only the empty-filter branch admits
	 * it, exactly the spec semantics. (Uptime occurrences always carry an
	 * environment; new-issue ones may not.)
	 */
	private List<MatchedChannel> matchChannels(String triggerType, long projectId, String environment) {
		return jdbc.sql("""
				SELECT id, type, url FROM notification_channel
				WHERE enabled = true
				  AND ? = ANY(triggers)
				  AND (cardinality(project_filter) = 0 OR ? = ANY(project_filter))
				  AND (cardinality(environment_filter) = 0 OR ? = ANY(environment_filter))
				""")
			.param(triggerType)
			.param(projectId)
			.param(environment)
			.query((rs, i) -> new MatchedChannel(rs.getLong("id"), rs.getString("type"), rs.getString("url")))
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

	/**
	 * Deep link for an incident notification. There is no per-monitor route, so
	 * this targets the Uptime status page (built from {@code outpost.public-url}
	 * like {@link #issueLink}); one click takes the reader from chat to the
	 * monitor overview.
	 */
	private String uptimeLink() {
		return properties.baseUrl() + "/uptime";
	}

	/** Deep link to the notification-channel settings, for the test payload's link. */
	private String settingsLink() {
		return properties.baseUrl() + "/settings";
	}
}
