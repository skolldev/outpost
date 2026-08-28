package dev.outpost.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Conventional Spring Security chain: the ingest surface is open (it does
 * its own DSN-key auth), the internal query API requires the signed session
 * cookie, the two bearer-token surfaces ({@code /api/0/**} for sentry-cli,
 * {@code /mcp} for the MCP Surface) are gated on the Scope each needs, and
 * admin-only endpoints are guarded by {@code @PreAuthorize}. CSRF is covered by
 * {@code SameSite=Lax} on the session cookie plus a JSON-only API.
 *
 * <p>{@code /mcp} is always mapped and always requires {@code telemetry:read};
 * there is no feature flag, because no token already means no access, which is
 * the posture {@code /api/0/**} has had since it shipped.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

	@Bean
	SecurityFilterChain filterChain(HttpSecurity http, SessionCookieService sessions, ApiTokenService apiTokens)
			throws Exception {
		return http
			.csrf(csrf -> csrf.disable())
			.cors(cors -> cors.configurationSource(ingestCorsSource()))
			.sessionManagement(session -> session.disable())
			.securityContext(context -> context.disable())
			.requestCache(cache -> cache.disable())
			.authorizeHttpRequests(auth -> auth
				.requestMatchers("/api/internal/auth/login").permitAll()
				.requestMatchers("/api/internal/**").authenticated()
				.requestMatchers("/api/0/**").hasAuthority("SCOPE_" + ApiTokenService.SCOPE_ARTIFACTS_WRITE)
				.requestMatchers("/mcp", "/mcp/**").hasAuthority("SCOPE_" + ApiTokenService.SCOPE_TELEMETRY_READ)
				.anyRequest().permitAll())
			.exceptionHandling(handling -> handling
				.authenticationEntryPoint((request, response, e) -> response.sendError(HttpStatus.UNAUTHORIZED.value()))
				.accessDeniedHandler(SecurityConfig::denyAccess))
			.addFilterBefore(new SessionCookieFilter(sessions), UsernamePasswordAuthenticationFilter.class)
			.addFilterBefore(new ApiTokenFilter(apiTokens), UsernamePasswordAuthenticationFilter.class)
			.build();
	}

	/**
	 * A recognised credential that does not carry the Scope a surface needs is a 403
	 * everywhere except {@code /mcp}, which answers 401.
	 *
	 * <p>The difference is not a taste one. An MCP client treats 401 as "present a
	 * credential" and 403 as "you are done here", so a token minted before
	 * {@code telemetry:read} existed would leave a client reporting a permanent
	 * failure rather than prompting for the token that would work. RFC 6750's
	 * {@code insufficient_scope} challenge names which Scope is missing, so the 401
	 * still says what a 403 would have. {@code /api/0/**} keeps its 403: sentry-cli
	 * has never re-authenticated on one, and changing it would only move the
	 * confusion.
	 */
	private static void denyAccess(HttpServletRequest request, HttpServletResponse response,
			org.springframework.security.access.AccessDeniedException e) throws IOException {
		if (ApiTokenFilter.mcpSurface(request.getRequestURI())) {
			response.setHeader("WWW-Authenticate",
					"Bearer error=\"insufficient_scope\", scope=\"" + ApiTokenService.SCOPE_TELEMETRY_READ + "\"");
			response.sendError(HttpStatus.UNAUTHORIZED.value());
			return;
		}
		response.sendError(HttpStatus.FORBIDDEN.value());
	}

	/** CORS for the ingest surface: browser SDKs post envelopes cross-origin. */
	private CorsConfigurationSource ingestCorsSource() {
		CorsConfiguration ingest = new CorsConfiguration();
		ingest.setAllowedOrigins(List.of("*"));
		ingest.setAllowedMethods(List.of("POST", "OPTIONS"));
		ingest.setAllowedHeaders(List.of("sentry-trace", "baggage", "content-type", "content-encoding",
				"x-sentry-auth", "authorization"));
		ingest.setExposedHeaders(List.of("x-sentry-rate-limits", "retry-after"));
		ingest.setAllowCredentials(false);
		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/api/{projectId:\\d+}/**", ingest);
		return source;
	}

	/**
	 * Populates the security context from a bearer token on the two surfaces that
	 * take one: {@code /api/0/**} for sentry-cli, and {@code /mcp} for the MCP
	 * Surface. Everything else is either session-authenticated or open, and a filter
	 * that ran everywhere would let a token reach {@code /api/internal/**} too.
	 */
	static final class ApiTokenFilter extends OncePerRequestFilter {

		private static final String MCP = "/mcp";

		/**
		 * Matched as "this path or a path below it", never as a bare prefix:
		 * {@code startsWith("/mcp")} would also authenticate {@code /mcp-anything},
		 * which the SPA fallback happily serves.
		 */
		private static final List<String> BEARER_SURFACES = List.of("/api/0", MCP);

		private final ApiTokenService apiTokens;

		ApiTokenFilter(ApiTokenService apiTokens) {
			this.apiTokens = apiTokens;
		}

		@Override
		protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
				throws ServletException, IOException {
			String header = request.getHeader("Authorization");
			if (bearerSurface(request.getRequestURI()) && header != null && header.startsWith("Bearer ")
					&& SecurityContextHolder.getContext().getAuthentication() == null) {
				apiTokens.authenticate(header.substring("Bearer ".length()).strip()).ifPresent(token -> {
					var authorities = token.scopes().stream()
						.map(scope -> new SimpleGrantedAuthority("SCOPE_" + scope))
						.toList();
					SecurityContextHolder.getContext().setAuthentication(
							UsernamePasswordAuthenticationToken.authenticated("token:" + token.name(), null, authorities));
				});
			}
			try {
				chain.doFilter(request, response);
			}
			finally {
				SecurityContextHolder.clearContext();
			}
		}

		/**
		 * Re-authenticate on the ASYNC dispatch too, which {@code OncePerRequestFilter}
		 * skips by default.
		 *
		 * <p>{@code /mcp}'s streamable transport answers a POST by committing SSE headers
		 * and going async; the container then re-dispatches, Spring Security re-runs its
		 * authorization on that dispatch, and an empty context there is not a clean 401 —
		 * the response is already committed, so {@code sendError} fails and the client
		 * sees a torn chunked stream instead. The bearer header is on the same request,
		 * so reading it again is all this needs.
		 */
		@Override
		protected boolean shouldNotFilterAsyncDispatch() {
			return false;
		}

		private static boolean bearerSurface(String uri) {
			for (String surface : BEARER_SURFACES) {
				if (below(uri, surface)) {
					return true;
				}
			}
			return false;
		}

		static boolean mcpSurface(String uri) {
			return below(uri, MCP);
		}

		private static boolean below(String uri, String surface) {
			return uri.equals(surface) || uri.startsWith(surface + "/");
		}
	}

	/** Populates the security context from the signed session cookie. */
	static final class SessionCookieFilter extends OncePerRequestFilter {

		private final SessionCookieService sessions;

		SessionCookieFilter(SessionCookieService sessions) {
			this.sessions = sessions;
		}

		@Override
		protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
				throws ServletException, IOException {
			SessionCookieService.Session session = sessions.verify(request);
			if (session != null) {
				var authentication = UsernamePasswordAuthenticationToken.authenticated(session.email(), null,
						List.of(new SimpleGrantedAuthority("ROLE_" + session.role().toUpperCase())));
				SecurityContextHolder.getContext().setAuthentication(authentication);
			}
			try {
				chain.doFilter(request, response);
			}
			finally {
				SecurityContextHolder.clearContext();
			}
		}
	}
}
