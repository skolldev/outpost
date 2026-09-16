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
	 * Minimum password length, per NIST SP 800-63B's floor of 8 rather than its
	 * recommended 15 (Installations are small, operator-network accounts).
	 * Raising this doesn't invalidate existing hashes since it's checked only
	 * at password-set time.
	 */
	public static final int MIN_PASSWORD_LENGTH = 8;

	public record User(long id, String email, String role, Instant createdAt) {
	}

	/**
	 * The password-length policy; callers phrase their own message around it.
	 * Length is counted in code points, not {@code String.length()}, so
	 * surrogate pairs (e.g. emoji) aren't double-counted.
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

	private User mapUser(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
		return new User(rs.getLong("id"), rs.getString("email"), rs.getString("role"),
				rs.getTimestamp("created_at").toInstant());
	}

	public Optional<User> authenticate(String email, String password) {
		return jdbc.sql("SELECT id, email, password_hash, role, created_at FROM app_user WHERE lower(email) = lower(?)")
			.param(email)
			.query((rs, i) -> new UserWithHash(mapUser(rs, i), rs.getString("password_hash")))
			.optional()
			.filter(u -> passwordEncoder.matches(password, u.passwordHash()))
			.map(UserWithHash::user);
	}

	public List<User> list() {
		return jdbc.sql("SELECT id, email, role, created_at FROM app_user ORDER BY id").query(this::mapUser).list();
	}

	public User create(String email, String password, String role) {
		return jdbc.sql("""
				INSERT INTO app_user (email, password_hash, role) VALUES (?, ?, ?)
				RETURNING id, email, role, created_at
				""")
			.param(email)
			.param(passwordEncoder.encode(password))
			.param(role)
			.query(this::mapUser)
			.single();
	}

	/**
	 * Re-verifies {@code currentPassword} and replaces the hash; returns false,
	 * touching nothing, if it doesn't match or the account is gone.
	 * Verify-and-write happen in one locked transaction so concurrent changes
	 * can't both verify against the same hash; per ADR-0012 existing Sessions
	 * survive the change.
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

	/**
	 * Resolves an account by email, the Session principal. Used by callers
	 * holding an {@code Authentication} that need the row's id, e.g. for token
	 * ownership.
	 */
	public Optional<User> findByEmail(String email) {
		return jdbc.sql("SELECT id, email, role, created_at FROM app_user WHERE lower(email) = lower(?)")
			.param(email)
			.query(this::mapUser)
			.optional();
	}

	public Optional<User> find(long id) {
		return jdbc.sql("SELECT id, email, role, created_at FROM app_user WHERE id = ?")
			.param(id)
			.query(this::mapUser)
			.optional();
	}

	/**
	 * Hard-deletes an account, refusing to remove the last Admin; returns false
	 * if the guard blocked it or the row was already gone. Guard and delete are
	 * one {@code FOR UPDATE}-locked statement so two Admins deleting each other
	 * concurrently can't both pass the check and leave the Installation with
	 * none.
	 */
	public boolean delete(long id) {
		return jdbc.sql("""
				DELETE FROM app_user
				WHERE id = ?
					AND (role <> 'admin'
						OR EXISTS (SELECT 1 FROM app_user other WHERE other.role = 'admin' AND other.id <> ? FOR UPDATE))
				""").param(id).param(id).update() == 1;
	}

	public long count() {
		return jdbc.sql("SELECT count(*) FROM app_user").query(Long.class).single();
	}
}
