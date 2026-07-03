package dev.outpost.auth;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/** Local accounts (§10): argon2id-hashed passwords, roles admin | member. */
@Service
public class UserService {

	public record User(long id, String email, String role, Instant createdAt) {
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

	public long count() {
		return jdbc.sql("SELECT count(*) FROM app_user").query(Long.class).single();
	}
}
