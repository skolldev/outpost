package dev.outpost.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Opaque bearer tokens for sentry-cli / CI (§10): shown once at creation,
 * SHA-256-hashed at rest (the tokens are 192-bit random, so a fast hash is
 * fine — unlike passwords), scoped (v1: {@code artifacts:write}).
 */
@Service
public class ApiTokenService {

	public static final String SCOPE_ARTIFACTS_WRITE = "artifacts:write";

	public record ApiToken(long id, String name, List<String> scopes, Instant createdAt) {
	}

	public record CreatedToken(ApiToken token, String secret) {
	}

	private final JdbcClient jdbc;
	private final SecureRandom random = new SecureRandom();

	public ApiTokenService(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public CreatedToken create(String name, List<String> scopes) {
		byte[] bytes = new byte[24];
		random.nextBytes(bytes);
		String secret = "outpost_" + HexFormat.of().formatHex(bytes);
		ApiToken token = jdbc.sql("""
				INSERT INTO api_token (name, token_hash, scopes) VALUES (?, ?, string_to_array(?, ','))
				RETURNING id, name, scopes, created_at
				""")
			.param(name)
			.param(hash(secret))
			.param(String.join(",", scopes))
			.query(ApiTokenService::mapToken)
			.single();
		return new CreatedToken(token, secret);
	}

	public List<ApiToken> list() {
		return jdbc.sql("SELECT id, name, scopes, created_at FROM api_token ORDER BY id")
			.query(ApiTokenService::mapToken)
			.list();
	}

	public boolean delete(long id) {
		return jdbc.sql("DELETE FROM api_token WHERE id = ?").param(id).update() > 0;
	}

	/** Resolves a presented bearer token, or empty if unknown. */
	public Optional<ApiToken> authenticate(String bearerToken) {
		return jdbc.sql("SELECT id, name, scopes, created_at FROM api_token WHERE token_hash = ?")
			.param(hash(bearerToken))
			.query(ApiTokenService::mapToken)
			.optional();
	}

	private static ApiToken mapToken(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
		return new ApiToken(rs.getLong("id"), rs.getString("name"),
				List.of((String[]) rs.getArray("scopes").getArray()), rs.getTimestamp("created_at").toInstant());
	}

	private static String hash(String token) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
