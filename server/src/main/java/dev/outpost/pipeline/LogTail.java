package dev.outpost.pipeline;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Live-tail fan-out (§9.3): the query API subscribes SSE emitters with a
 * filter predicate; {@link LogStore} publishes every stored record. Slow or
 * gone clients are dropped on the first failed send; a periodic comment keeps
 * idle connections alive through proxies.
 */
@Component
public class LogTail implements SmartLifecycle {

	private static final Logger log = LoggerFactory.getLogger(LogTail.class);
	private static final long HEARTBEAT_SECONDS = 25;

	private final Map<SseEmitter, Predicate<ProcessedLog>> subscribers = new ConcurrentHashMap<>();
	private final ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(runnable -> {
		Thread thread = new Thread(runnable, "log-tail-heartbeat");
		thread.setDaemon(true);
		return thread;
	});
	private volatile boolean running;

	public LogTail() {
		heartbeat.scheduleAtFixedRate(this::sendHeartbeats, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
	}

	@Override
	public void start() {
		running = true;
	}

	/**
	 * Stops in the default (highest) lifecycle phase, i.e. before the web
	 * server's graceful-shutdown wait — open SSE requests would otherwise
	 * stall shutdown until its timeout.
	 */
	@Override
	public void stop() {
		running = false;
		heartbeat.shutdownNow();
		subscribers.keySet().forEach(this::drop);
	}

	@Override
	public boolean isRunning() {
		return running;
	}

	public SseEmitter subscribe(Predicate<ProcessedLog> filter) {
		SseEmitter emitter = new SseEmitter(0L); // no server-side timeout; drop on send failure
		emitter.onCompletion(() -> subscribers.remove(emitter));
		emitter.onError(e -> subscribers.remove(emitter));
		subscribers.put(emitter, filter);
		// Commit headers right away so the client sees the stream open.
		if (!send(emitter, event -> event.send(SseEmitter.event().comment("connected")))) {
			drop(emitter);
		}
		return emitter;
	}

	public void publish(List<ProcessedLog> records) {
		if (subscribers.isEmpty() || records.isEmpty()) {
			return;
		}
		subscribers.forEach((emitter, filter) -> {
			for (ProcessedLog record : records) {
				if (filter.test(record) && !send(emitter, e -> e.send(record, MediaType.APPLICATION_JSON))) {
					drop(emitter);
					return;
				}
			}
		});
	}

	private void sendHeartbeats() {
		subscribers.keySet().forEach(emitter -> {
			if (!send(emitter, event -> event.send(SseEmitter.event().comment("keepalive")))) {
				drop(emitter);
			}
		});
	}

	private boolean send(SseEmitter emitter, EmitterAction action) {
		try {
			// SseEmitter.send is not thread-safe; publishes come from multiple
			// ingest workers plus the heartbeat thread.
			synchronized (emitter) {
				action.run(emitter);
			}
			return true;
		}
		catch (IOException | RuntimeException e) {
			log.debug("dropping live-tail subscriber: {}", e.toString());
			return false;
		}
	}

	private void drop(SseEmitter emitter) {
		subscribers.remove(emitter);
		try {
			emitter.complete();
		}
		catch (RuntimeException ignored) {
			// already broken — nothing to complete
		}
	}

	private interface EmitterAction {
		void run(SseEmitter emitter) throws IOException;
	}
}
