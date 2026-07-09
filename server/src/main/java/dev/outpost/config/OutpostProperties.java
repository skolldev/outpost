package dev.outpost.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "outpost")
public record OutpostProperties(String publicUrl, Admin admin) {

	public record Admin(String email, String password) {
	}
}
