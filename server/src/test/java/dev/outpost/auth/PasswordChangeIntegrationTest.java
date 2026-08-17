package dev.outpost.auth;

import static org.assertj.core.api.Assertions.assertThat;

import dev.outpost.TestcontainersConfiguration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.client.RestTemplate;

/**
 * Self-service password change: a signed-in Outpost User re-states their current
 * password and picks a new one. Identity comes from the session, so a Member can
 * change their own password and nothing in the request body can retarget it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "outpost.admin.email=admin@test.local", "outpost.admin.password=test-password" })
@Import(TestcontainersConfiguration.class)
class PasswordChangeIntegrationTest {

	private static final String ADMIN_EMAIL = "admin@test.local";
	private static final String ADMIN_PASSWORD = "test-password";
	private static final String MEMBER_EMAIL = "member@test.local";
	private static final String MEMBER_PASSWORD = "member-password";
	private static final String NEW_PASSWORD = "brand-new-password";

	@LocalServerPort
	int port;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	UserService users;

	final RestTemplate rest = new RestTemplate();

	String memberCookie;

	@BeforeEach
	void setUp() {
		rest.setErrorHandler(new org.springframework.web.client.NoOpResponseErrorHandler());
		jdbc.sql("DELETE FROM app_user").update();
		users.create(ADMIN_EMAIL, ADMIN_PASSWORD, "admin");
		users.create(MEMBER_EMAIL, MEMBER_PASSWORD, "member");
		memberCookie = login(MEMBER_EMAIL, MEMBER_PASSWORD);
	}

	@Test
	void changesThePasswordAndTheNewOneLogsIn() {
		ResponseEntity<Map> response = changePassword(memberCookie,
				Map.of("current_password", MEMBER_PASSWORD, "new_password", NEW_PASSWORD));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
		assertThat(loginStatus(MEMBER_EMAIL, NEW_PASSWORD)).isEqualTo(HttpStatus.OK);
		assertThat(loginStatus(MEMBER_EMAIL, MEMBER_PASSWORD)).isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	/** Per ADR-0012 the cookie is signed, not stored: changing the password does not revoke it. */
	@Test
	void leavesTheSessionThatMadeTheChangeUsable() {
		changePassword(memberCookie, Map.of("current_password", MEMBER_PASSWORD, "new_password", NEW_PASSWORD));

		assertThat(get("/api/internal/auth/me", memberCookie).getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	@Test
	void rejectsAWrongCurrentPassword() {
		ResponseEntity<Map> response = changePassword(memberCookie,
				Map.of("current_password", "not-the-password", "new_password", NEW_PASSWORD));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(loginStatus(MEMBER_EMAIL, MEMBER_PASSWORD)).isEqualTo(HttpStatus.OK);
		assertThat(loginStatus(MEMBER_EMAIL, NEW_PASSWORD)).isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	@Test
	void rejectsANewPasswordShorterThanTheMinimum() {
		ResponseEntity<Map> response = changePassword(memberCookie,
				Map.of("current_password", MEMBER_PASSWORD, "new_password", "short"));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(response.getBody()).containsEntry("detail", "new password must be at least 8 characters");
		assertThat(loginStatus(MEMBER_EMAIL, MEMBER_PASSWORD)).isEqualTo(HttpStatus.OK);
	}

	/** Eight is eight characters as a person counts them, not as UTF-16 does. */
	@Test
	void rejectsANewPasswordOfFewerThanEightCodePoints() {
		ResponseEntity<Map> response = changePassword(memberCookie,
				Map.of("current_password", MEMBER_PASSWORD, "new_password", "😀😀😀😀"));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(loginStatus(MEMBER_EMAIL, MEMBER_PASSWORD)).isEqualTo(HttpStatus.OK);
	}

	@Test
	void requiresASession() {
		ResponseEntity<Map> response = rest.exchange(url("/api/internal/auth/password"), HttpMethod.POST,
				new HttpEntity<>(Map.of("current_password", MEMBER_PASSWORD, "new_password", NEW_PASSWORD)), Map.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(loginStatus(MEMBER_EMAIL, MEMBER_PASSWORD)).isEqualTo(HttpStatus.OK);
	}

	/** The session principal decides whose password changes — an email in the body is ignored. */
	@Test
	void ignoresAnEmailInTheBody() {
		ResponseEntity<Map> response = changePassword(memberCookie,
				Map.of("email", ADMIN_EMAIL, "current_password", MEMBER_PASSWORD, "new_password", NEW_PASSWORD));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
		assertThat(loginStatus(MEMBER_EMAIL, NEW_PASSWORD)).isEqualTo(HttpStatus.OK);
		assertThat(loginStatus(ADMIN_EMAIL, ADMIN_PASSWORD)).isEqualTo(HttpStatus.OK);
		assertThat(loginStatus(ADMIN_EMAIL, NEW_PASSWORD)).isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	@Test
	void adminsCanChangeTheirOwnPasswordToo() {
		String adminCookie = login(ADMIN_EMAIL, ADMIN_PASSWORD);

		ResponseEntity<Map> response = changePassword(adminCookie,
				Map.of("current_password", ADMIN_PASSWORD, "new_password", "rotated-admin-password"));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
		assertThat(loginStatus(ADMIN_EMAIL, "rotated-admin-password")).isEqualTo(HttpStatus.OK);
	}

	@SuppressWarnings("rawtypes")
	private ResponseEntity<Map> changePassword(String cookie, Map<String, String> body) {
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.COOKIE, cookie);
		return rest.exchange(url("/api/internal/auth/password"), HttpMethod.POST, new HttpEntity<>(body, headers),
				Map.class);
	}

	@SuppressWarnings("rawtypes")
	private ResponseEntity<Map> get(String path, String cookie) {
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.COOKIE, cookie);
		return rest.exchange(url(path), HttpMethod.GET, new HttpEntity<>(headers), Map.class);
	}

	@SuppressWarnings("rawtypes")
	private ResponseEntity<Map> attemptLogin(String email, String password) {
		return rest.postForEntity(url("/api/internal/auth/login"), Map.of("email", email, "password", password),
				Map.class);
	}

	private String login(String email, String password) {
		ResponseEntity<Map> response = attemptLogin(email, password);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		String setCookie = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
		assertThat(setCookie).isNotNull();
		return setCookie.split(";")[0];
	}

	private HttpStatusCode loginStatus(String email, String password) {
		return attemptLogin(email, password).getStatusCode();
	}

	private String url(String path) {
		return "http://localhost:" + port + path;
	}
}
