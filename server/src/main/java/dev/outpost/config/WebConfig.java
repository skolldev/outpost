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
 * Serves the Angular bundle, which the image build stages into
 * {@code src/main/resources/static/} before {@code bootJar} so that one jar is
 * the whole product. This replaces the nginx container that used to sit in
 * front of this process; the caching and SPA-fallback rules below are the ones
 * that config expressed.
 *
 * <p>The app is served at the host root only. Sub-path deployments are not
 * supported: {@code <base href>} is baked into the bundle at build time and
 * would have to agree with a run-time context path, with nothing to check that
 * it does.
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
		// Hashed build artifacts (outputHashing: all) — safe to cache forever.
		// Deliberately excludes unhashed public/ assets like favicon.ico, which
		// fall through to the catch-all below.
		registry.addResourceHandler("/*.js", "/*.css", "/media/**")
			.addResourceLocations(BUNDLE)
			.setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).immutable());

		// index.html references those hashed bundles by name, so it must always
		// revalidate — as must anything else unhashed.
		registry.addResourceHandler("/**")
			.addResourceLocations(BUNDLE)
			.setCacheControl(CacheControl.noCache())
			.resourceChain(true)
			.addResolver(new SpaFallbackResolver());
	}

	/**
	 * Answers unknown paths with {@code index.html} so Angular's client-side
	 * routes survive a cold load. Two kinds of path must <em>not</em> fall back:
	 * <ul>
	 * <li>server-owned prefixes — an unmapped {@code /api/...} has to stay a 404
	 * rather than become a 200 carrying the app shell;</li>
	 * <li>anything that looks like a file — a bundle that failed to build must
	 * 404 rather than return HTML the browser then rejects on content type,
	 * which is a far more confusing failure to debug.</li>
	 * </ul>
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
