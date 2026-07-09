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
 * Conventional Spring Security chain (§10): the ingest surface is open (it does
 * its own DSN-key auth), the internal query API requires the signed session
 * cookie, admin-only endpoints are guarded by {@code @PreAuthorize}. CSRF is
 * covered by {@code SameSite=Lax} on the session cookie plus a JSON-only API.
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
				.anyRequest().permitAll())
			.exceptionHandling(handling -> handling
				.authenticationEntryPoint((request, response, e) -> response.sendError(HttpStatus.UNAUTHORIZED.value())))
			.addFilterBefore(new SessionCookieFilter(sessions), UsernamePasswordAuthenticationFilter.class)
			.addFilterBefore(new ApiTokenFilter(apiTokens), UsernamePasswordAuthenticationFilter.class)
			.build();
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

	/** Populates the security context from a sentry-cli bearer token (§10) on the /api/0/** surface. */
	static final class ApiTokenFilter extends OncePerRequestFilter {

		private final ApiTokenService apiTokens;

		ApiTokenFilter(ApiTokenService apiTokens) {
			this.apiTokens = apiTokens;
		}

		@Override
		protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
				throws ServletException, IOException {
			String header = request.getHeader("Authorization");
			if (request.getRequestURI().startsWith("/api/0/") && header != null && header.startsWith("Bearer ")
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
