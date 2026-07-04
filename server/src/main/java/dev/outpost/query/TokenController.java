package dev.outpost.query;

import dev.outpost.auth.ApiTokenService;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** sentry-cli API tokens (§8, §10): create (secret shown once), list, revoke. Admin-only. */
@RestController
@RequestMapping("/api/internal/tokens")
@PreAuthorize("hasRole('ADMIN')")
public class TokenController {

	public record CreateToken(String name) {
	}

	/** Creation response — the only time the secret is shown (§10). */
	public record CreatedTokenResponse(long id, String name, java.util.List<String> scopes,
			java.time.Instant createdAt, String token) {
	}

	private final ApiTokenService tokens;

	public TokenController(ApiTokenService tokens) {
		this.tokens = tokens;
	}

	@GetMapping
	public List<ApiTokenService.ApiToken> list() {
		return tokens.list();
	}

	@PostMapping
	public ResponseEntity<?> create(@RequestBody CreateToken request) {
		if (request.name() == null || request.name().isBlank()) {
			return ResponseEntity.badRequest().body(Map.of("detail", "name required"));
		}
		ApiTokenService.CreatedToken created = tokens.create(request.name().strip(),
				List.of(ApiTokenService.SCOPE_ARTIFACTS_WRITE));
		return ResponseEntity.status(HttpStatus.CREATED)
			.body(new CreatedTokenResponse(created.token().id(), created.token().name(), created.token().scopes(),
					created.token().createdAt(), created.secret()));
	}

	@DeleteMapping("/{id}")
	public ResponseEntity<Void> delete(@PathVariable long id) {
		return tokens.delete(id) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
	}
}
