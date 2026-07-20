package dev.outpost.notifications;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Delivers one notification payload over HTTP POST with a small number of
 * in-process retries and linear backoff (ADR 0005: best-effort, no durable
 * queue). Success is a 2xx response; any non-2xx, timeout, or connection error
 * is retried up to {@code max-attempts}, and the last failure's detail is
 * returned for the history row. A failure is always a {@link Result}, never an
 * exception — the caller records outcomes, it does not handle throws.
 *
 * <p>No egress/SSRF filtering (ADR 0006): the URL is admin-configured on a
 * single-tenant install, so private and internal destinations are intended.
 */
@Component
public class WebhookSender {

	/** {@code errorDetail} is {@code null} exactly when {@code success}. */
	public record Result(boolean success, String errorDetail) {
	}

	private static final Logger log = LoggerFactory.getLogger(WebhookSender.class);
	private static final int MAX_ERROR_LENGTH = 1000;

	private final HttpClient client = HttpClient.newBuilder()
		.followRedirects(HttpClient.Redirect.NORMAL)
		.connectTimeout(Duration.ofSeconds(10))
		.executor(Executors.newVirtualThreadPerTaskExecutor())
		.build();

	private final int maxAttempts;
	private final Duration requestTimeout;
	private final long backoffMillis;

	public WebhookSender(@Value("${outpost.notifications.max-attempts:3}") int maxAttempts,
			@Value("${outpost.notifications.request-timeout-seconds:10}") int requestTimeoutSeconds,
			@Value("${outpost.notifications.retry-backoff-millis:500}") long backoffMillis) {
		if (maxAttempts < 1) {
			throw new IllegalArgumentException("outpost.notifications.max-attempts must be positive");
		}
		this.maxAttempts = maxAttempts;
		this.requestTimeout = Duration.ofSeconds(requestTimeoutSeconds);
		this.backoffMillis = backoffMillis;
	}

	/** POSTs {@code jsonBody} to {@code url}, retrying transient failures with backoff. */
	public Result send(String url, String jsonBody) {
		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
			.timeout(requestTimeout)
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(jsonBody))
			.build();

		String lastError = null;
		for (int attempt = 1; attempt <= maxAttempts; attempt++) {
			try {
				HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
				int status = response.statusCode();
				if (status >= 200 && status < 300) {
					return new Result(true, null);
				}
				lastError = "HTTP " + status;
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return new Result(false, "interrupted");
			}
			catch (Exception e) {
				lastError = truncate(e.toString());
			}
			if (attempt < maxAttempts && !backoff(attempt)) {
				break;
			}
		}
		log.debug("webhook delivery to {} failed after {} attempts: {}", url, maxAttempts, lastError);
		return new Result(false, lastError);
	}

	/** Linear backoff between attempts; returns false if interrupted so the loop stops. */
	private boolean backoff(int attempt) {
		if (backoffMillis <= 0) {
			return true;
		}
		try {
			Thread.sleep(backoffMillis * attempt);
			return true;
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	private static String truncate(String message) {
		if (message == null) {
			return null;
		}
		return message.length() <= MAX_ERROR_LENGTH ? message : message.substring(0, MAX_ERROR_LENGTH);
	}
}
