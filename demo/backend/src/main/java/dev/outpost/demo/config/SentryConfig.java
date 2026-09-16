package dev.outpost.demo.config;

import io.sentry.Sentry;
import io.sentry.SentryOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Set here rather than application.yml: the Spring Boot 4 starter's relaxed-binding
 * property names for these options aren't documented yet.
 */
@Configuration
public class SentryConfig {

	@Bean
	public Sentry.OptionsConfiguration<SentryOptions> sentryOptionsCustomizer() {
		return options -> {
			options.getLogs().setEnabled(true);
			// Outpost drops session envelope items.
			options.setEnableAutoSessionTracking(false);
		};
	}
}
