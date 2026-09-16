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
 * Sets the Sentry user from the X-Demo-User header so backend events match the
 * frontend's selected persona. No cleanup needed: Sentry's Spring filter isolates scopes per request.
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
