package dev.outpost.query;

import dev.outpost.auth.ApiTokenService;
import dev.outpost.auth.UserService;
import dev.outpost.config.OutpostProperties;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * API Tokens: create (the secret is shown once), list, revoke.
 *
 * <p>Not Admin-only, per ADR-0017: a Member may mint a Personal Token carrying
 * {@code telemetry:read} for their own agent. Authority is enforced on the two
 * axes that differ by role rather than by a guard on the class —
 *
 * <ul>
 * <li><b>Scope.</b> {@code artifacts:write} uploads to Installation resources
 * and stays Admin-only; {@code telemetry:read} grants no capability a Member
 * lacks in the UI, only a different transport for it.
 * <li><b>Ownership.</b> An Admin chooses between a Personal Token and an
 * Installation Token that outlives every account; a Member only ever creates
 * their own. Listing and revoking follow: an Admin sees all tokens, a Member
 * only their own.
 * </ul>
 */
@RestController
@RequestMapping("/api/internal/tokens")
public class TokenController {

	/**
	 * {@code scopes} is required — a token carrying none authenticates nothing, so
	 * an omitted list is a mistake worth reporting rather than a default worth
	 * guessing. {@code personal} may be omitted, and then means the only kind the
	 * caller can create: an Installation Token for an Admin (what this endpoint has
	 * always minted), a Personal Token for a Member.
	 */
	public record CreateToken(String name, List<String> scopes, @Nullable Boolean personal) {
	}

	/**
	 * Creation response — the only time the secret is shown.
	 *
	 * <p>{@code mcpUrl} is the MCP Surface's address on this Installation, sent so
	 * the one-time reveal can render a paste-ready client configuration. The browser
	 * cannot derive it: {@code location.origin} loses a reverse-proxy sub-path, and
	 * hand-assembling the URL is the onboarding step that most often goes wrong.
	 */
	public record CreatedTokenResponse(long id, String name, List<String> scopes, Instant createdAt,
			@Nullable Long ownerUserId, @Nullable String ownerEmail, String token, String mcpUrl) {
	}

	/** Every Scope this endpoint knows; anything else in a request is a typo. */
	private static final Set<String> KNOWN_SCOPES = Set.of(ApiTokenService.SCOPE_TELEMETRY_READ,
			ApiTokenService.SCOPE_ARTIFACTS_WRITE);

	/** What a Member may grant. An Admin may grant everything in {@link #KNOWN_SCOPES}. */
	private static final Set<String> MEMBER_SCOPES = Set.of(ApiTokenService.SCOPE_TELEMETRY_READ);

	private final ApiTokenService tokens;

	private final UserService users;

	private final OutpostProperties properties;

	public TokenController(ApiTokenService tokens, UserService users, OutpostProperties properties) {
		this.tokens = tokens;
		this.users = users;
		this.properties = properties;
	}

	@GetMapping
	public List<ApiTokenService.ApiToken> list(Authentication authentication) {
		if (isAdmin(authentication)) {
			return tokens.list();
		}
		return users.findByEmail(authentication.getName())
			.map(user -> tokens.listOwnedBy(user.id()))
			.orElseGet(List::of);
	}

	@PostMapping
	public ResponseEntity<?> create(@RequestBody CreateToken request, Authentication authentication) {
		if (request.name() == null || request.name().isBlank()) {
			return ResponseEntity.badRequest().body(Map.of("detail", "name required"));
		}
		// Details are shown to the user verbatim by the settings page, so they are
		// written as sentences.
		Set<String> scopes = new LinkedHashSet<>(request.scopes() == null ? List.<String>of() : request.scopes());
		scopes.remove(null);
		if (scopes.isEmpty()) {
			return ResponseEntity.badRequest().body(Map.of("detail", "Select at least one scope."));
		}
		if (!KNOWN_SCOPES.containsAll(scopes)) {
			return ResponseEntity.badRequest().body(Map.of("detail", "Unknown scope."));
		}
		boolean admin = isAdmin(authentication);
		if (!admin && !MEMBER_SCOPES.containsAll(scopes)) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN)
				.body(Map.of("detail", "Only an admin can grant the " + ApiTokenService.SCOPE_ARTIFACTS_WRITE
						+ " scope."));
		}
		boolean personal = request.personal() == null ? !admin : request.personal();
		if (!admin && !personal) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN)
				.body(Map.of("detail", "Only an admin can create an installation token."));
		}
		Long ownerUserId = null;
		if (personal) {
			// The Session outlives the account it was granted to (ADR-0012), so a
			// caller can still be authenticated after their row is gone. Minting a
			// Personal Token for nobody would silently produce an Installation Token.
			UserService.User owner = users.findByEmail(authentication.getName()).orElse(null);
			if (owner == null) {
				return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
					.body(Map.of("detail", "Your account no longer exists."));
			}
			ownerUserId = owner.id();
		}
		ApiTokenService.CreatedToken created = tokens.create(request.name().strip(), List.copyOf(scopes), ownerUserId);
		ApiTokenService.ApiToken token = created.token();
		return ResponseEntity.status(HttpStatus.CREATED)
			.body(new CreatedTokenResponse(token.id(), token.name(), token.scopes(), token.createdAt(),
					token.ownerUserId(), token.ownerEmail(), created.secret(), properties.baseUrl() + "/mcp"));
	}

	/**
	 * Revokes a token. A Member may only revoke their own, and someone else's
	 * answers 404 exactly as a token that never existed does — a 403 would confirm
	 * the id.
	 */
	@DeleteMapping("/{id}")
	public ResponseEntity<Void> delete(@PathVariable long id, Authentication authentication) {
		boolean deleted = isAdmin(authentication) ? tokens.delete(id)
				: users.findByEmail(authentication.getName())
					.map(user -> tokens.deleteOwnedBy(id, user.id()))
					.orElse(false);
		return deleted ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
	}

	private static boolean isAdmin(Authentication authentication) {
		return authentication.getAuthorities()
			.stream()
			.anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
	}
}
