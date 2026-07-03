package dev.outpost.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Session endpoints for the UI: login, logout, current user. */
@RestController
@RequestMapping("/api/internal/auth")
public class AuthController {

	public record Credentials(String email, String password) {
	}

	private final UserService users;
	private final SessionCookieService sessions;

	public AuthController(UserService users, SessionCookieService sessions) {
		this.users = users;
		this.sessions = sessions;
	}

	@PostMapping("/login")
	public ResponseEntity<Map<String, String>> login(@RequestBody Credentials credentials,
			HttpServletResponse response) {
		if (credentials.email() == null || credentials.password() == null) {
			return ResponseEntity.badRequest().body(Map.of("detail", "email and password are required"));
		}
		return users.authenticate(credentials.email(), credentials.password()).map(user -> {
			sessions.issue(response, user.email(), user.role());
			return ResponseEntity.ok(Map.of("email", user.email(), "role", user.role()));
		}).orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED)
			.body(Map.of("detail", "invalid credentials")));
	}

	@PostMapping("/logout")
	public ResponseEntity<Void> logout(HttpServletResponse response) {
		sessions.clear(response);
		return ResponseEntity.noContent().build();
	}

	@GetMapping("/me")
	public ResponseEntity<Map<String, String>> me(HttpServletRequest request) {
		SessionCookieService.Session session = sessions.verify(request);
		if (session == null) {
			return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
		}
		return ResponseEntity.ok(Map.of("email", session.email(), "role", session.role()));
	}
}
