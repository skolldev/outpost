package dev.outpost.config;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * Serves the Angular bundle, staged into {@code src/main/resources/static/} by
 * the image build so one jar is the whole product; served at the host root only.
 * Sub-path deployments aren't supported — {@code <base href>} is baked into the
 * bundle at build time, with nothing to check it matches the deployed path.
 */
@Configuration
class WebConfig implements WebMvcConfigurer {

	private static final String BUNDLE = "classpath:/static/";

	/**
	 * Request prefixes this server answers itself. Relative (no leading slash)
	 * because that is the form a resource path reaches the resolver in.
	 */
	private static final List<String> SERVER_OWNED = List.of("api/", "actuator/", "healthz", "readyz");

	@Override
	public void addResourceHandlers(ResourceHandlerRegistry registry) {
		// Hashed build artifacts (outputHashing: all) are safe to cache forever; unhashed assets like favicon.ico fall through to the catch-all below.
		registry.addResourceHandler("/*.js", "/*.css", "/media/**")
			.addResourceLocations(BUNDLE)
			.setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).immutable());

		// index.html references the hashed bundles by name, so it (and anything else unhashed) must always revalidate.
		registry.addResourceHandler("/**")
			.addResourceLocations(BUNDLE)
			.setCacheControl(CacheControl.noCache())
			.resourceChain(true)
			.addResolver(new SpaFallbackResolver());
	}

	/**
	 * Falls back to {@code index.html} for unknown paths so Angular's client-side routes
	 * survive a cold load, except server-owned prefixes (kept a 404) and paths that look
	 * like a file (a failed build must 404, not serve HTML with the wrong content type).
	 */
	private static final class SpaFallbackResolver extends PathResourceResolver {

		@Override
		protected Resource getResource(String resourcePath, Resource location) throws IOException {
			Resource requested = super.getResource(resourcePath, location);
			if (requested != null) {
				return requested;
			}
			if (resourcePath.contains(".") || SERVER_OWNED.stream().anyMatch(resourcePath::startsWith)) {
				return null;
			}
			return super.getResource("index.html", location);
		}
	}
}
