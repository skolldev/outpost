package dev.outpost.demo.config;

import io.sentry.Sentry;
import io.sentry.SentryOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Options set in code rather than application.yml where the Spring Boot 4
 * starter's relaxed-binding property names aren't documented yet — the
 * OptionsConfiguration bean is applied by the starter regardless.
 */
@Configuration
public class SentryConfig {

	@Bean
	public Sentry.OptionsConfiguration<SentryOptions> sentryOptionsCustomizer() {
		return options -> {
			options.getLogs().setEnabled(true);
			// Outpost drops session envelope items — don't send them.
			options.setEnableAutoSessionTracking(false);
		};
	}
}
