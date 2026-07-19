package dev.outpost.uptime;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.Executors;
import org.springframework.stereotype.Component;

/**
 * Performs a single uptime probe: HTTP GET, success = final status &lt; 400
 * (redirects followed) within the monitor's timeout. Timeouts, connection
 * errors, and 4xx/5xx are failures — a failure is a <em>result</em>, never an
 * exception to the caller.
 *
 * <p>SSRF note: monitor URLs are admin-configured on a self-hosted install,
 * so probing internal addresses is accepted by design (no allowlist).
 *
 * <p>{@link HttpRequest#timeout} covers time-to-response-headers; the
 * discarding body handler minimizes (but does not hard-cap) body-read time —
 * negligible for health endpoints.
 */
@Component
public class UptimeProber {

	public record ProbeResult(boolean success, Integer statusCode, int latencyMs, String error) {
	}

	private static final int MAX_ERROR_LENGTH = 500;

	private final HttpClient client = HttpClient.newBuilder()
		.followRedirects(HttpClient.Redirect.NORMAL)
		.connectTimeout(Duration.ofSeconds(30))
		.executor(Executors.newVirtualThreadPerTaskExecutor())
		.build();

	public ProbeResult probe(String url, int timeoutSeconds) {
		long started = System.nanoTime();
		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create(url))
				.timeout(Duration.ofSeconds(timeoutSeconds))
				.GET()
				.build();
			HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
			int latency = elapsedMillis(started);
			int status = response.statusCode();
			return new ProbeResult(status < 400, status, latency, status < 400 ? null : "HTTP " + status);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return new ProbeResult(false, null, elapsedMillis(started), "interrupted");
		}
		catch (java.io.IOException | RuntimeException e) {
			return new ProbeResult(false, null, elapsedMillis(started), truncate(e.toString()));
		}
	}

	/** {@code null} when valid; a human-readable problem otherwise. */
	public static String validateUrl(String url) {
		if (url == null || url.isBlank()) {
			return "url is required";
		}
		URI uri;
		try {
			uri = URI.create(url);
		}
		catch (IllegalArgumentException e) {
			return "url is not a valid URI";
		}
		if (!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme())) {
			return "url must use http or https";
		}
		if (uri.getHost() == null || uri.getHost().isBlank()) {
			return "url must have a host";
		}
		return null;
	}

	private static int elapsedMillis(long startedNanos) {
		return (int) Math.min(Integer.MAX_VALUE, (System.nanoTime() - startedNanos) / 1_000_000);
	}

	private static String truncate(String message) {
		return message.length() <= MAX_ERROR_LENGTH ? message : message.substring(0, MAX_ERROR_LENGTH);
	}
}
