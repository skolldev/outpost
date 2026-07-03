package dev.outpost.ingest;

import tools.jackson.databind.JsonNode;
import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * DSN-key authentication for the envelope endpoint (§4.2). Key lookup order:
 * {@code X-Sentry-Auth} header, {@code sentry_key} query parameter, {@code dsn}
 * field in the envelope header. The key must be an active key of the project.
 */
@Component
public class IngestAuthenticator {

	private static final Pattern SENTRY_KEY = Pattern.compile("sentry_key\\s*=\\s*([0-9a-fA-F]{32})");

	private final JdbcClient jdbc;

	public IngestAuthenticator(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Extracts the public key from header/query/envelope-header, or null. */
	public String extractKey(String sentryAuthHeader, String sentryKeyParam, JsonNode envelopeHeader) {
		if (sentryAuthHeader != null) {
			Matcher m = SENTRY_KEY.matcher(sentryAuthHeader);
			if (m.find()) {
				return m.group(1);
			}
		}
		if (sentryKeyParam != null && !sentryKeyParam.isBlank()) {
			return sentryKeyParam;
		}
		if (envelopeHeader != null && envelopeHeader.hasNonNull("dsn")) {
			try {
				String userInfo = URI.create(envelopeHeader.get("dsn").asText()).getUserInfo();
				if (userInfo != null) {
					int colon = userInfo.indexOf(':');
					return colon < 0 ? userInfo : userInfo.substring(0, colon);
				}
			}
			catch (IllegalArgumentException ignored) {
				// unparseable dsn — treated as no key
			}
		}
		return null;
	}

	public boolean isValidKey(long projectId, String publicKey) {
		if (publicKey == null) {
			return false;
		}
		return jdbc.sql("SELECT count(*) FROM project_key WHERE project_id = ? AND public_key = ? AND is_active")
			.param(projectId)
			.param(publicKey)
			.query(Long.class)
			.single() > 0;
	}
}
