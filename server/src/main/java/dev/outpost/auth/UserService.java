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

	/**
	 * Resolves an account by email, which is what a Session carries as its
	 * principal — callers holding an {@code Authentication} and needing the row's
	 * id (token ownership, for one) go through here.
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
	 * Hard-deletes an account, refusing to remove the last remaining Admin.
	 * Returns false when the row survived that guard, or was already gone.
	 *
	 * <p>The guard is not made redundant by the controller's no-self-deletion
	 * check: two Admins, each holding a live Session, can delete each other in
	 * turn. {@code AdminBootstrap} only seeds when {@code app_user} is completely
	 * empty, and no endpoint can promote a Member, so an Installation left with
	 * Members and no Admin is unadministrable forever — recoverable only by
	 * database surgery. Do not remove this.
	 *
	 * <p>Guard and delete are one statement, and the surviving Admin is locked
	 * with {@code FOR UPDATE} so two concurrent deletions cannot each observe the
	 * other's Admin and both proceed — under read committed the plain subquery
	 * count the guard reads as would let exactly that happen. The lock lets two
	 * Admins deleting each other at the same instant deadlock, which surfaces as
	 * a 500 rather than the 409 one of them deserves; that is the better failure,
	 * because the alternative outcome is an Installation with no Admin at all.
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
