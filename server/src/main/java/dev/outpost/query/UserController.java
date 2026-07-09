package dev.outpost.query;

import dev.outpost.auth.UserService;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** User administration (§8): list + create, admin-only. */
@RestController
@RequestMapping("/api/internal/users")
@PreAuthorize("hasRole('ADMIN')")
public class UserController {

	public record CreateUser(String email, String password, String role) {
	}

	private final UserService users;

	public UserController(UserService users) {
		this.users = users;
	}

	@GetMapping
	public List<UserService.User> list() {
		return users.list();
	}

	@PostMapping
	public ResponseEntity<?> create(@RequestBody CreateUser request) {
		if (request.email() == null || !request.email().contains("@")) {
			return ResponseEntity.badRequest().body(Map.of("detail", "valid email required"));
		}
		if (request.password() == null || request.password().length() < 8) {
			return ResponseEntity.badRequest().body(Map.of("detail", "password must be at least 8 characters"));
		}
		String role = "admin".equals(request.role()) ? "admin" : "member";
		try {
			return ResponseEntity.status(HttpStatus.CREATED).body(users.create(request.email(), request.password(), role));
		}
		catch (DuplicateKeyException e) {
			return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("detail", "email already registered"));
		}
	}
}
