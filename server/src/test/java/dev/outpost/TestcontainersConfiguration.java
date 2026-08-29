package dev.outpost;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	/** Pinned in one place: a benchmark run against a different major version is not a comparison. */
	public static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:17-alpine");

	/**
	 * How much dearer a random page is than a sequential one. Postgres still ships
	 * 4.0, a ratio measured on rotating disks, and it is the one planner constant
	 * that decides every question these fixtures ask — index-only scan or heap read.
	 *
	 * <p><b>Why it is set rather than inherited (#185).</b> At 4.0 the log timeline's
	 * two candidate plans price within a percent of each other on the densest weekly
	 * partition: reading ~600 heap blocks sequentially, or ~140 index blocks at 4x
	 * apiece. {@code VACUUM ANALYZE}'s random sample moves the estimate by well under
	 * a percent, which was enough to flip the winner between runs — so
	 * {@code LogTimelinePerformanceTest} failed intermittently on the same code and
	 * the same dataset, its verdict decided by a coin toss rather than by the query.
	 * At 1.1 the covering index wins by a wide margin, on every shape, every run.
	 *
	 * <p>1.1 is the SSD-representative value, and the {@code db} service in
	 * {@code docker-compose.yml} passes the same one — so this is the fixture
	 * matching the deployment, not a fixture tuned until the guard was green. Change
	 * one and change the other, or the guards resume measuring a database nobody runs.
	 *
	 * <p>{@code fsync=off} is repeated because {@code withCommand} replaces the whole
	 * command, and Testcontainers' own default is where that flag comes from. Losing
	 * it costs the seeder minutes and buys correctness nothing: the container is
	 * discarded either way.
	 */
	private static final String[] COMMAND = { "postgres", "-c", "fsync=off", "-c", "random_page_cost=1.1" };

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer(POSTGRES_IMAGE).withCommand(COMMAND);
	}
}
