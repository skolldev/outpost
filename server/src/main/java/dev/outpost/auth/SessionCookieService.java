package dev.outpost.auth;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Stateless server-signed session cookie (§10): value is
 * {@code base64url(email).base64url(role).expiry.base64url(hmacSha256)}. The signing
 * secret is generated on first boot and kept in the {@code setting} table so
 * sessions survive restarts and are valid across replicas.
 */
@Service
public class SessionCookieService {

	public static final String COOKIE_NAME = "outpost_session";
	private static final Duration SESSION_TTL = Duration.ofDays(14);
	private static final String SECRET_SETTING = "session.signing-secret";

	private final byte[] secret;

	public SessionCookieService(JdbcClient jdbc) {
		this.secret = loadOrCreateSecret(jdbc);
	}

	private static byte[] loadOrCreateSecret(JdbcClient jdbc) {
		byte[] fresh = new byte[32];
		new SecureRandom().nextBytes(fresh);
		// Single INSERT..ON CONFLICT..RETURNING round trip: first boot stores the
		// fresh secret, later boots (and concurrent replicas) read the stored one.
		String stored = jdbc.sql("""
				INSERT INTO setting (key, value) VALUES (?, ?)
				ON CONFLICT (key) DO UPDATE SET value = setting.value
				RETURNING value
				""").param(SECRET_SETTING).param(HexFormat.of().formatHex(fresh)).query(String.class).single();
		return HexFormat.of().parseHex(stored);
	}

	/** A session principal: who + role. */
	public record Session(String email, String role) {
	}

	public void issue(HttpServletResponse response, String email, String role) {
		long expiry = Instant.now().plus(SESSION_TTL).getEpochSecond();
		String payload = base64(email.getBytes(StandardCharsets.UTF_8)) + "." + base64(role.getBytes(StandardCharsets.UTF_8))
				+ "." + expiry;
		String value = payload + "." + base64(hmac(payload));
		response.addCookie(build(value, (int) SESSION_TTL.toSeconds()));
	}

	public void clear(HttpServletResponse response) {
		response.addCookie(build("", 0));
	}

	/** Verifies the cookie on {@code request}; null when absent/invalid/expired. */
	public Session verify(HttpServletRequest request) {
		Cookie[] cookies = request.getCookies();
		if (cookies == null) {
			return null;
		}
		for (Cookie cookie : cookies) {
			if (COOKIE_NAME.equals(cookie.getName())) {
				return verifyValue(cookie.getValue());
			}
		}
		return null;
	}

	private Session verifyValue(String value) {
		String[] parts = value.split("\\.");
		if (parts.length != 4) {
			return null;
		}
		String payload = parts[0] + "." + parts[1] + "." + parts[2];
		try {
			byte[] expected = hmac(payload);
			byte[] actual = Base64.getUrlDecoder().decode(parts[3]);
			if (!MessageDigest.isEqual(expected, actual)) {
				return null;
			}
			if (Long.parseLong(parts[2]) < Instant.now().getEpochSecond()) {
				return null;
			}
			return new Session(new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8),
					new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
		}
		catch (IllegalArgumentException e) {
			return null;
		}
	}

	private Cookie build(String value, int maxAge) {
		Cookie cookie = new Cookie(COOKIE_NAME, value);
		cookie.setHttpOnly(true);
		cookie.setPath("/");
		cookie.setMaxAge(maxAge);
		cookie.setAttribute("SameSite", "Lax");
		return cookie;
	}

	private byte[] hmac(String payload) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(secret, "HmacSHA256"));
			return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
		}
		catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	private static String base64(byte[] bytes) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}
}
