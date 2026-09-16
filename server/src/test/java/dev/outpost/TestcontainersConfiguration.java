package dev.outpost;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	public static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:17-alpine");

	/**
	 * {@code random_page_cost=1.1} must match the {@code db} service in docker-compose.yml,
	 * or these fixtures stop measuring the database production runs on (#185). {@code fsync=off}
	 * is repeated because {@code withCommand} replaces Testcontainers' entire default command.
	 */
	private static final String[] COMMAND = { "postgres", "-c", "fsync=off", "-c", "random_page_cost=1.1" };

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer(POSTGRES_IMAGE).withCommand(COMMAND);
	}
}
