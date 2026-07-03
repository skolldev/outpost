package dev.outpost.auth;

import dev.outpost.config.OutpostProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * First-run bootstrap (§10): seeds the initial admin from
 * {@code OUTPOST_ADMIN_EMAIL}/{@code OUTPOST_ADMIN_PASSWORD} when no users
 * exist yet. Never touches an already-populated user table.
 */
@Component
public class AdminBootstrap implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

	private final UserService users;
	private final OutpostProperties properties;

	public AdminBootstrap(UserService users, OutpostProperties properties) {
		this.users = users;
		this.properties = properties;
	}

	@Override
	public void run(ApplicationArguments args) {
		if (users.count() > 0) {
			return;
		}
		String email = properties.admin().email();
		String password = properties.admin().password();
		if (email == null || email.isBlank() || password == null || password.isBlank()) {
			log.warn("no users exist and OUTPOST_ADMIN_EMAIL/OUTPOST_ADMIN_PASSWORD are not set — "
					+ "the UI is unreachable until an admin is seeded");
			return;
		}
		users.create(email, password, "admin");
		log.info("seeded initial admin user {}", email);
	}
}
