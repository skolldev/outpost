package dev.outpost.config;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "outpost")
public record OutpostProperties(String publicUrl, Admin admin) {

	public record Admin(String email, String password) {
	}

	/**
	 * The public base URL as {@code scheme://host[:port][/path]} with any trailing
	 * slash stripped — the prefix under which the app is reached, honoring a
	 * reverse-proxy sub-path. Append {@code /issues/<id>} etc. to build deep links.
	 */
	public String baseUrl() {
		URI base = URI.create(publicUrl);
		String authority = base.getPort() > 0 ? base.getHost() + ":" + base.getPort() : base.getHost();
		String path = base.getPath() == null ? "" : base.getPath().replaceAll("/+$", "");
		return base.getScheme() + "://" + authority + path;
	}

	/**
	 * DSN format: {@code scheme://<public_key>@<host>[/<path>]/<project_id>}. A
	 * configured path prefix is preserved for installations served below the host
	 * root.
	 */
	public String dsn(String publicKey, long projectId) {
		URI base = URI.create(publicUrl);
		String authority = base.getPort() > 0 ? base.getHost() + ":" + base.getPort() : base.getHost();
		String path = base.getPath() == null ? "" : base.getPath().replaceAll("/+$", "");
		String key = publicKey == null ? "" : publicKey;
		return base.getScheme() + "://" + key + "@" + authority + path + "/" + projectId;
	}
}
