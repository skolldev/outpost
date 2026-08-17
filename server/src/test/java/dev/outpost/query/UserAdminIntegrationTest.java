package dev.outpost.query;

import static org.assertj.core.api.Assertions.assertThat;

import dev.outpost.TestcontainersConfiguration;
import java.util.List;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.client.NoOpResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

/**
 * Outpost User administration: an Admin can delete another account, but not
 * their own and not the last remaining Admin. See ADR-0012 for what deletion
 * deliberately leaves alone (the deleted user's Session).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "outpost.admin.email=admin@test.local", "outpost.admin.password=test-password" })
@Import(TestcontainersConfiguration.class)
class UserAdminIntegrationTest {

	private static final String USERS = "/api/internal/users";

	@LocalServerPort
	int port;

	@Autowired
	JdbcClient jdbc;

	final RestTemplate rest = new RestTemplate();

	String adminCookie;

	@BeforeEach
	void setUp() {
		rest.setErrorHandler(new NoOpResponseErrorHandler());
		jdbc.sql("DELETE FROM app_user WHERE email <> 'admin@test.local'").update();
		adminCookie = login("admin@test.local", "test-password");
	}

	@Test
	void deletesAMemberWhileOneAdminRemains() {
		long memberId = createUser("member@test.local", "member-password", "member");

		assertThat(delete(USERS + "/" + memberId, adminCookie).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

		assertThat(emails(list())).containsExactly("admin@test.local");
	}

	@Test
	void rejectsSelfDeletion() {
		long adminId = idOf(list(), "admin@test.local");

		ResponseEntity<Map> response = delete(USERS + "/" + adminId, adminCookie);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(response.getBody()).containsEntry("detail", "You cannot delete your own account.");
		assertThat(emails(list())).contains("admin@test.local");
	}

	/**
	 * Two Admins holding live Sessions can delete each other in turn — the second
	 * deletion is the one that would leave the Installation with no Admin, and no
	 * endpoint can promote anyone back. Deleting the second Admin's account leaves
	 * their Session working (ADR-0012), which is exactly how a request arrives
	 * asking to delete the last Admin without being self-deletion.
	 */
	@Test
	void rejectsDeletingTheLastAdmin() {
		long secondAdminId = createUser("second@test.local", "second-password", "admin");
		String secondAdminCookie = login("second@test.local", "second-password");
		long bootstrapAdminId = idOf(list(), "admin@test.local");

		assertThat(delete(USERS + "/" + secondAdminId, adminCookie).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

		ResponseEntity<Map> response = delete(USERS + "/" + bootstrapAdminId, secondAdminCookie);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(response.getBody()).containsEntry("detail", "You cannot delete the last remaining admin.");
		assertThat(emails(list())).containsExactly("admin@test.local");
	}

	@Test
	void returnsNotFoundForAnUnknownId() {
		assertThat(delete(USERS + "/999999", adminCookie).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	// ------------------------------------------------------------------ helpers

	private long createUser(String email, String password, String role) {
		ResponseEntity<Map> response = rest.exchange(url(USERS), HttpMethod.POST,
				new HttpEntity<>(Map.of("email", email, "password", password, "role", role), authHeaders(adminCookie)),
				Map.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		return ((Number) response.getBody().get("id")).longValue();
	}

	@SuppressWarnings("unchecked")
	private List<Map<String, Object>> list() {
		ResponseEntity<List> response = rest.exchange(url(USERS), HttpMethod.GET,
				new HttpEntity<>(authHeaders(adminCookie)), List.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		return response.getBody();
	}

	private List<String> emails(List<Map<String, Object>> users) {
		return users.stream().map(user -> (String) user.get("email")).toList();
	}

	private long idOf(List<Map<String, Object>> users, String email) {
		return users.stream()
			.filter(user -> email.equals(user.get("email")))
			.map(user -> ((Number) user.get("id")).longValue())
			.findFirst()
			.orElseThrow();
	}

	private ResponseEntity<Map> delete(String path, String cookie) {
		return rest.exchange(url(path), HttpMethod.DELETE, new HttpEntity<>(authHeaders(cookie)), Map.class);
	}

	private String login(String email, String password) {
		ResponseEntity<Map> response = rest.postForEntity(url("/api/internal/auth/login"),
				Map.of("email", email, "password", password), Map.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		return response.getHeaders().getFirst(HttpHeaders.SET_COOKIE).split(";")[0];
	}

	private HttpHeaders authHeaders(String cookie) {
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.COOKIE, cookie);
		headers.setContentType(MediaType.APPLICATION_JSON);
		return headers;
	}

	private String url(String path) {
		return "http://localhost:" + port + path;
	}
}
