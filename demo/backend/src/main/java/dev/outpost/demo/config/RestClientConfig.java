package dev.outpost.demo.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

	@Bean
	public RestClient selfClient(RestClient.Builder builder, @Value("${demo.self-url}") String selfUrl) {
		return builder.baseUrl(selfUrl).build();
	}
}
