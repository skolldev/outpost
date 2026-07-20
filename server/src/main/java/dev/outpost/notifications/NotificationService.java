package dev.outpost.notifications;

import dev.outpost.config.OutpostProperties;
import java.time.Instant;
import java.util.List;
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
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

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
 * <p>This slice delivers {@code new_issue} to {@code generic_json} channels and
 * backs the Admin test-send action (#44) via {@link #testSend}: a {@code test}
 * occurrence bypasses matching (the channel is named directly) but runs the same
 * format → history → async-deliver pipeline, so a green test proves the whole
 * path. Teams formatting (#46) and other triggers (#45) slot in behind the same
 * seam without touching callers.
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

	private record MatchedChannel(long id, String url) {
	}

	/** A channel resolved for test-send: everything delivery and formatting need. */
	private record ChannelRow(long id, String name, String type, String url, boolean enabled) {
	}

	private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

	private final JdbcClient jdbc;
	private final GenericJsonFormatter genericJsonFormatter;
	private final WebhookSender sender;
	private final OutpostProperties properties;
	private final ObjectMapper mapper;

	// Recreated on each start() so a SmartLifecycle stop()/start() cycle (e.g.
	// the test-context pause) gets a live executor.
	private ExecutorService deliveries;
	private volatile boolean running;

	public NotificationService(JdbcClient jdbc, GenericJsonFormatter genericJsonFormatter, WebhookSender sender,
			OutpostProperties properties, ObjectMapper mapper) {
		this.jdbc = jdbc;
		this.genericJsonFormatter = genericJsonFormatter;
		this.sender = sender;
		this.properties = properties;
		this.mapper = mapper;
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
			case NotificationOccurrence.Test test -> deliverTest(test);
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
		String payload = formatForType(occurrence, channel.type());
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
	 * Formats a test occurrence for the channel's type. Generic JSON reuses the
	 * documented public contract; Teams gets a minimal valid MessageCard body
	 * ({@code {"text": ...}}) — rich Adaptive Card formatting is #46, but a test
	 * must still deliver a well-formed message so the round-trip is real.
	 */
	private String formatForType(NotificationOccurrence.Test occurrence, String type) {
		if ("teams".equals(type)) {
			ObjectNode root = mapper.createObjectNode();
			root.put("text", occurrence.message());
			return root.toString();
		}
		return genericJsonFormatter.format(occurrence, new NotificationContext(null, null, settingsLink()));
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

	/** Deep link to the notification-channel settings, for the test payload's link. */
	private String settingsLink() {
		return properties.baseUrl() + "/settings";
	}
}
