package dev.outpost.query;

import dev.outpost.auth.UserService;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** User administration: list, create, delete — admin-only. */
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
		if (!UserService.isAcceptablePassword(request.password())) {
			return ResponseEntity.badRequest()
				.body(Map.of("detail", "password must be at least " + UserService.MIN_PASSWORD_LENGTH + " characters"));
		}
		String role = "admin".equals(request.role()) ? "admin" : "member";
		try {
			return ResponseEntity.status(HttpStatus.CREATED).body(users.create(request.email(), request.password(), role));
		}
		catch (DuplicateKeyException e) {
			return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("detail", "email already registered"));
		}
	}

	/**
	 * Hard-deletes an account. Deleting yourself is refused — the Session outlives
	 * the account (ADR-0012), so it would leave an Admin driving a UI whose
	 * account is gone. Deleting the last Admin is refused by
	 * {@link UserService#delete}.
	 */
	@DeleteMapping("/{id}")
	public ResponseEntity<?> delete(@PathVariable long id, Authentication authentication) {
		UserService.User user = users.find(id).orElse(null);
		if (user == null) {
			return ResponseEntity.notFound().build();
		}
		// Both details are shown to the Admin verbatim by the users settings page,
		// so they are written as sentences.
		if (user.email().equalsIgnoreCase(authentication.getName())) {
			return ResponseEntity.status(HttpStatus.CONFLICT)
				.body(Map.of("detail", "You cannot delete your own account."));
		}
		if (!users.delete(id)) {
			// delete() also returns false for a row that vanished between the two
			// statements, which is a 404 rather than a guard refusal.
			if (users.find(id).isEmpty()) {
				return ResponseEntity.notFound().build();
			}
			return ResponseEntity.status(HttpStatus.CONFLICT)
				.body(Map.of("detail", "You cannot delete the last remaining admin."));
		}
		return ResponseEntity.noContent().build();
	}
}
