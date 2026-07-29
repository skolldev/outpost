package dev.outpost.notifications;

import dev.outpost.config.OutpostProperties;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The one deep module behind the publisher seam (#41). Owns everything existing
 * code must not know about: channel matching, history persistence, per-type
 * payload formatting, and asynchronous HTTP delivery with retries. Callers see
 * only {@link NotificationPublisher#publish}.
 *
 * <p>Delivery is fully decoupled from ingest and uptime (ADR 0005):
 * {@code publish} hands the occurrence to a virtual-thread executor and returns,
 * so a slow receiver or saturated executor can never backpressure the caller. A
 * {@code pending} history row is written up front and moved to
 * {@code sent}/{@code failed} once the send resolves; a shutdown mid-send leaves
 * the row stale rather than redelivering.
 *
 * <p>Per-type formatting lives behind the {@link NotificationFormatter} seam
 * (resolved by {@link NotificationFormatters}), so a new channel type slots in as
 * a formatter bean without touching this delivery path, the matching query, or
 * any caller.
 *
 * <p>Deliveries are rate-capped per channel (ADR 0010, #47) — see
 * {@link #reserveSlot}.
 */
@Component
public class NotificationService implements NotificationPublisher, SmartLifecycle {

	/**
	 * Outcome of an Admin test-send. {@code NOT_FOUND}/{@code DISABLED} are refusals
	 * the endpoint maps to 404/409; {@code FAILED} carries the receiver error the UI
	 * shows inline; {@code UNAVAILABLE} means the delivery executor was stopped.
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

	private record ChannelRow(long id, String name, String type, String url, boolean enabled) {
	}

	private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

	/** Non-configurable by design — see ADR 0010. */
	static final int RATE_CAP_PER_MINUTE = 10;

	/** Distinct from {@code EventIssueLock}'s namespace. */
	private static final int RATE_LOCK_NAMESPACE = 0x4E4F5449; // "NOTI"

	private final JdbcClient jdbc;
	private final NotificationFormatters formatters;
	private final WebhookSender sender;
	private final OutpostProperties properties;
	private final TransactionTemplate rateLimitTransaction;

	// Recreated on each start() so a SmartLifecycle stop()/start() cycle (e.g. the
	// test-context pause) gets a live executor.
	private ExecutorService deliveries;
	private volatile boolean running;

	public NotificationService(JdbcClient jdbc, NotificationFormatters formatters, WebhookSender sender,
			OutpostProperties properties, PlatformTransactionManager transactionManager) {
		this.jdbc = jdbc;
		this.formatters = formatters;
		this.sender = sender;
		this.properties = properties;
		this.rateLimitTransaction = new TransactionTemplate(transactionManager);
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
	 * Both incident triggers deliver identically — they differ only in the payload
	 * the formatter writes — so the delivery path is shared. The summary uses the
	 * probed URL because a Monitor has no name.
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
	 * The payload depends only on the channel's <em>type</em>, so it is formatted
	 * once per distinct type — and always before any pending row is written, so a
	 * formatting failure can't strand a row stuck at {@code pending}.
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
			OptionalLong reserved = reserveSlot(channel.id(), occurrence.triggerType(), summary);
			if (reserved.isEmpty()) {
				continue; // Over the cap: a suppressed row is recorded, no HTTP delivery.
			}
			WebhookSender.Result result = sender.send(channel.url(), payload);
			recordOutcome(reserved.getAsLong(), result);
		}
	}

	/**
	 * Enforce the per-channel rate cap (ADR 0010) and, if there is room, reserve a
	 * delivery slot — empty means the caller must skip the HTTP send.
	 *
	 * <p>A delivery is any non-{@code suppressed} row, since
	 * {@code pending}/{@code sent}/{@code failed} each consumed an attempt. The
	 * advisory lock serializes count-and-insert per channel so concurrent
	 * virtual-thread deliveries cannot overshoot the cap.
	 */
	private OptionalLong reserveSlot(long channelId, String triggerType, String summary) {
		return rateLimitTransaction.execute(status -> {
			lockChannelForRate(channelId);
			long recent = jdbc.sql("""
					SELECT count(*) FROM notification_history
					WHERE channel_id = ?
					  AND status <> 'suppressed'
					  AND created_at > now() - interval '1 minute'
					""").param(channelId).query(Long.class).single();
			if (recent >= RATE_CAP_PER_MINUTE) {
				jdbc.sql("""
						INSERT INTO notification_history (channel_id, trigger_type, status, summary, error_detail)
						VALUES (?, ?, 'suppressed', ?, ?)
						""")
					.param(channelId)
					.param(triggerType)
					.param(summary)
					.param("rate limit: over " + RATE_CAP_PER_MINUTE + " notifications/minute for this channel")
					.update();
				return OptionalLong.empty();
			}
			return OptionalLong.of(insertPending(channelId, triggerType, summary));
		});
	}

	/**
	 * Keyed on the channel, so one channel's lock never blocks another's, and
	 * transaction-scoped so it is released at commit — before any HTTP send, never
	 * across the network. Same idiom as {@code EventIssueLock}, but a separate
	 * concern, so it stays local to this module.
	 */
	private void lockChannelForRate(long channelId) {
		jdbc.sql("SELECT pg_advisory_xact_lock(?, ?)")
			.param(RATE_LOCK_NAMESPACE)
			.param(Long.hashCode(channelId))
			.query(rs -> {
			});
	}

	/**
	 * Admin test-send (#44): deliver to one named channel, bypassing matching but
	 * respecting {@code enabled} and per-type formatting. Re-checks both after
	 * {@link #testSend}'s pre-check, since the channel can change while the task
	 * sits in the executor queue. Deliberately skips {@link #reserveSlot} — a
	 * verification send must not be refused by the rate cap (ADR 0010).
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
	 * Fire a test-send at one channel and wait for the outcome. Refusals are decided
	 * up front so the endpoint answers without touching the network; the delivery
	 * itself goes through the same executor as real notifications — that is what
	 * makes a green test prove the whole path — and is awaited so the Admin sees the
	 * outcome inline.
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

	/** Null if the channel is gone. */
	private ChannelRow loadChannel(long channelId) {
		return jdbc.sql("SELECT id, name, type, url, enabled FROM notification_channel WHERE id = ?")
			.param(channelId)
			.query((rs, i) -> new ChannelRow(rs.getLong("id"), rs.getString("name"), rs.getString("type"),
					rs.getString("url"), rs.getBoolean("enabled")))
			.optional()
			.orElse(null);
	}

	/**
	 * Project display fields paired with the supplied deep link; null if the Project
	 * is gone. The caller supplies the link because it differs per trigger.
	 */
	private NotificationContext resolveContext(long projectId, String link) {
		return jdbc.sql("SELECT slug, name FROM project WHERE id = ?")
			.param(projectId)
			.query((rs, i) -> new NotificationContext(rs.getString("name"), rs.getString("slug"), link))
			.optional()
			.orElse(null);
	}

	/**
	 * Channels that fire on this occurrence, of any type — the
	 * {@link NotificationFormatter} seam formats per type at delivery. An empty
	 * filter array matches everything.
	 *
	 * <p>A non-empty {@code environment_filter} never matches an occurrence with no
	 * environment, because {@code null = ANY(...)} is not true — so only the
	 * empty-filter branch admits it, which is the intended semantics rather than an
	 * accident of the SQL.
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
	 * Built from {@code outpost.public-url} like the DSN in
	 * {@code ProjectController}, so a reverse-proxy sub-path prefix is preserved and
	 * the link resolves through the same base the UI is served under.
	 */
	private String issueLink(long issueId) {
		return properties.baseUrl() + "/issues/" + issueId;
	}

	/** Targets the Uptime overview because there is no per-monitor route. */
	private String uptimeLink() {
		return properties.baseUrl() + "/uptime";
	}

	private String settingsLink() {
		return properties.baseUrl() + "/settings";
	}
}
