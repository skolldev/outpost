package dev.outpost.config;

import java.io.IOException;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * Serves the Angular UI from the jar and falls back to index.html for
 * client-side routes. API and ingest paths are never rewritten.
 */
@Configuration
public class SpaWebConfig implements WebMvcConfigurer {

	@Override
	public void addResourceHandlers(ResourceHandlerRegistry registry) {
		registry.addResourceHandler("/**")
			.addResourceLocations("classpath:/static/")
			.resourceChain(true)
			.addResolver(new PathResourceResolver() {
				@Override
				protected Resource getResource(String resourcePath, Resource location) throws IOException {
					if (resourcePath.startsWith("api/") || resourcePath.startsWith("actuator/")) {
						return null;
					}
					Resource resource = location.createRelative(resourcePath);
					if (resource.exists() && resource.isReadable()) {
						return resource;
					}
					ClassPathResource index = new ClassPathResource("/static/index.html");
					return index.exists() ? index : null;
				}
			});
	}
}
