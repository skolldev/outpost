package dev.outpost.auth;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Local accounts: argon2id-hashed passwords, roles admin | member. */
@Service
public class UserService {

	/**
	 * Minimum password length. NIST SP 800-63B puts the verifier floor at 8 and
	 * recommends 15 for a password used as the only factor, which is what login is
	 * here; we take the floor, because an Installation runs inside the operator's
	 * own network and its accounts are few and human-administered. What we do keep
	 * from the same document is the part that matters more: no composition rules,
	 * no forced rotation. Raising this invalidates no existing hash — it is checked
	 * only when a password is set.
	 */
	public static final int MIN_PASSWORD_LENGTH = 8;

	public record User(long id, String email, String role, Instant createdAt) {
	}

	/**
	 * The one password policy — callers phrase their own message around it. Length
	 * is counted in code points, so eight emoji are eight characters rather than
	 * the sixteen UTF-16 units {@code String.length} would report.
	 */
	public static boolean isAcceptablePassword(String password) {
		return password != null && password.codePointCount(0, password.length()) >= MIN_PASSWORD_LENGTH;
	}

	private final JdbcClient jdbc;
	private final PasswordEncoder passwordEncoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();

	public UserService(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	private record UserWithHash(User user, String passwordHash) {
	}

	public Optional<User> authenticate(String email, String password) {
		return jdbc.sql("SELECT id, email, password_hash, role, created_at FROM app_user WHERE lower(email) = lower(?)")
			.param(email)
			.query((rs, i) -> new UserWithHash(new User(rs.getLong("id"), rs.getString("email"), rs.getString("role"),
					rs.getTimestamp("created_at").toInstant()), rs.getString("password_hash")))
			.optional()
			.filter(u -> passwordEncoder.matches(password, u.passwordHash()))
			.map(UserWithHash::user);
	}

	public List<User> list() {
		return jdbc.sql("SELECT id, email, role, created_at FROM app_user ORDER BY id")
			.query((rs, i) -> new User(rs.getLong("id"), rs.getString("email"), rs.getString("role"),
					rs.getTimestamp("created_at").toInstant()))
			.list();
	}

	public User create(String email, String password, String role) {
		return jdbc.sql("""
				INSERT INTO app_user (email, password_hash, role) VALUES (?, ?, ?)
				RETURNING id, email, role, created_at
				""")
			.param(email)
			.param(passwordEncoder.encode(password))
			.param(role)
			.query((rs, i) -> new User(rs.getLong("id"), rs.getString("email"), rs.getString("role"),
					rs.getTimestamp("created_at").toInstant()))
			.single();
	}

	/**
	 * Re-verifies {@code currentPassword} and replaces the hash, returning false —
	 * and touching nothing — when it does not match or the account is gone. Both
	 * halves happen in one transaction with the row locked, so two changes racing
	 * each other cannot both verify against the same hash and have the loser
	 * overwrite a password the owner has already been told is theirs. Callers still
	 * decide <em>whose</em> account this is; per ADR-0012 any Session already issued
	 * survives the change.
	 */
	@Transactional
	public boolean changePassword(String email, String currentPassword, String newPassword) {
		Optional<String> currentHash = jdbc
			.sql("SELECT password_hash FROM app_user WHERE lower(email) = lower(?) FOR UPDATE")
			.param(email)
			.query(String.class)
			.optional();
		if (currentHash.isEmpty() || !passwordEncoder.matches(currentPassword, currentHash.get())) {
			return false;
		}
		jdbc.sql("UPDATE app_user SET password_hash = ? WHERE lower(email) = lower(?)")
			.param(passwordEncoder.encode(newPassword))
			.param(email)
			.update();
		return true;
	}

	public long count() {
		return jdbc.sql("SELECT count(*) FROM app_user").query(Long.class).single();
	}
}
