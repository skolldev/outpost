package dev.outpost.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Opaque bearer tokens for sentry-cli / CI and the MCP Surface: shown once at
 * creation, SHA-256-hashed at rest (the tokens are 192-bit random, so a fast
 * hash is fine — unlike passwords), scoped ({@code artifacts:write} for uploads,
 * {@code telemetry:read} for the MCP Surface).
 *
 * <p>Per ADR-0017 a token either belongs to an Outpost User — a Personal Token,
 * which the {@code ON DELETE CASCADE} on {@code owner_user_id} revokes the
 * instant that account is deleted — or to nobody, an Installation Token that
 * outlives whoever created it.
 */
@Service
public class ApiTokenService {

	public static final String SCOPE_ARTIFACTS_WRITE = "artifacts:write";

	/**
	 * Read access to telemetry, carried today by the tokens the MCP Surface accepts.
	 * Named for the capability rather than for the surface — {@code mcp:read} would
	 * have to be minted a second time the day a documented public read API lands,
	 * and two scopes granting the same permission is a decision nobody could undo.
	 */
	public static final String SCOPE_TELEMETRY_READ = "telemetry:read";

	/**
	 * A token row. {@code ownerUserId} and {@code ownerEmail} are both null for an
	 * Installation Token and both populated for a Personal Token — the email is
	 * joined in rather than resolved by the caller so that a list of tokens costs
	 * one statement, and it is carried on the same record as the id so no caller
	 * has to remember which of two shapes it holds.
	 */
	public record ApiToken(long id, String name, List<String> scopes, Instant createdAt, @Nullable Long ownerUserId,
			@Nullable String ownerEmail) {
	}

	public record CreatedToken(ApiToken token, String secret) {
	}

	private static final String SELECT = """
			SELECT t.id, t.name, t.scopes, t.created_at, t.owner_user_id, u.email AS owner_email
			FROM api_token t LEFT JOIN app_user u ON u.id = t.owner_user_id
			""";

	private final JdbcClient jdbc;
	private final SecureRandom random = new SecureRandom();

	public ApiTokenService(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Mints a token; a null {@code ownerUserId} makes it an Installation Token. */
	public CreatedToken create(String name, List<String> scopes, @Nullable Long ownerUserId) {
		byte[] bytes = new byte[24];
		random.nextBytes(bytes);
		String secret = "outpost_" + HexFormat.of().formatHex(bytes);
		long id = jdbc.sql("""
				INSERT INTO api_token (name, token_hash, scopes, owner_user_id)
				VALUES (?, ?, string_to_array(?, ','), ?)
				RETURNING id
				""")
			.param(name)
			.param(hash(secret))
			.param(String.join(",", scopes))
			.param(ownerUserId)
			.query(Long.class)
			.single();
		return new CreatedToken(find(id).orElseThrow(), secret);
	}

	/** Every token on the Installation — the Admin's view. */
	public List<ApiToken> list() {
		return jdbc.sql(SELECT + "ORDER BY t.id").query(ApiTokenService::mapToken).list();
	}

	/** Only the Personal Tokens of one Outpost User — the Member's view. */
	public List<ApiToken> listOwnedBy(long ownerUserId) {
		return jdbc.sql(SELECT + "WHERE t.owner_user_id = ? ORDER BY t.id")
			.param(ownerUserId)
			.query(ApiTokenService::mapToken)
			.list();
	}

	public Optional<ApiToken> find(long id) {
		return jdbc.sql(SELECT + "WHERE t.id = ?").param(id).query(ApiTokenService::mapToken).optional();
	}

	public boolean delete(long id) {
		return jdbc.sql("DELETE FROM api_token WHERE id = ?").param(id).update() > 0;
	}

	/**
	 * Revokes a token only if it is the given user's own. Returns false for a token
	 * that belongs to somebody else exactly as it does for one that does not exist,
	 * so callers cannot use the outcome to probe for other people's tokens.
	 */
	public boolean deleteOwnedBy(long id, long ownerUserId) {
		return jdbc.sql("DELETE FROM api_token WHERE id = ? AND owner_user_id = ?")
			.param(id)
			.param(ownerUserId)
			.update() > 0;
	}

	/**
	 * Resolves a presented bearer token, or empty if unknown. Runs on every request
	 * to {@code /api/0/**} and {@code /mcp}: a unique-index hit on {@code token_hash}
	 * plus a primary-key join for the owner, which the filter does not read but
	 * which keeps one token shape rather than a second, half-populated one.
	 */
	public Optional<ApiToken> authenticate(String bearerToken) {
		return jdbc.sql(SELECT + "WHERE t.token_hash = ?")
			.param(hash(bearerToken))
			.query(ApiTokenService::mapToken)
			.optional();
	}

	private static ApiToken mapToken(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
		// wasNull() reports on the most recent read, so the owner has to be resolved
		// before any other column is touched.
		long ownerUserId = rs.getLong("owner_user_id");
		Long owner = rs.wasNull() ? null : ownerUserId;
		return new ApiToken(rs.getLong("id"), rs.getString("name"),
				List.of((String[]) rs.getArray("scopes").getArray()), rs.getTimestamp("created_at").toInstant(), owner,
				rs.getString("owner_email"));
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
