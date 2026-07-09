package dev.outpost.demo.config;

import io.sentry.Sentry;
import io.sentry.protocol.User;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Mirrors the frontend's selected demo persona onto backend events and logs:
 * the Angular app sends the username in X-Demo-User, so both projects' events
 * carry the same user. Sentry's Spring filter isolates scopes per request, so
 * no cleanup is needed.
 */
@Component
public class DemoUserFilter extends OncePerRequestFilter {

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String username = request.getHeader("X-Demo-User");
		if (username != null && !username.isBlank()) {
			User user = new User();
			user.setId(username);
			user.setUsername(username);
			user.setEmail(username + "@example.com");
			Sentry.setUser(user);
		}
		chain.doFilter(request, response);
	}
}
