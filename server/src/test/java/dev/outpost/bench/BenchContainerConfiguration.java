package dev.outpost.bench;

import dev.outpost.TestcontainersConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A Postgres tuned for the retrieval benchmark, replacing the defaults
 * {@code TestcontainersConfiguration} leaves alone.
 *
 * <p>Explicit beats accidental. Postgres ships with {@code shared_buffers=128MB},
 * and running a multi-gigabyte dataset against that means every query is cold
 * I/O — which is a real operating condition, and a perfectly legitimate thing to
 * measure, but only if it was chosen. Stumbled into, it silently turns every
 * latency number in the report into a statement about disk rather than about the
 * query. The settings here are read back with {@code SHOW} and printed as report
 * conditions, so what is quoted is what the server actually ran with, not what
 * this file asked for.
 *
 * <p>{@code synchronous_commit=off} and the raised {@code maintenance_work_mem}
 * are for the seed, not the measurement: they cut minutes off loading millions of
 * rows and cannot affect a read-only benchmark.
 */
@TestConfiguration(proxyBeanMethods = false)
public class BenchContainerConfiguration {

	/**
	 * Docker's 64 MB default for {@code /dev/shm} is enough for the guards and not
	 * for this: a parallel hash or sort over millions of rows allocates its shared
	 * segment there, and running out surfaces as {@code could not resize shared
	 * memory segment}, which reads like a Postgres bug rather than a container
	 * setting.
	 */
	private static final long SHARED_MEMORY_BYTES = 2L * 1024 * 1024 * 1024;

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer(TestcontainersConfiguration.POSTGRES_IMAGE)
			.withSharedMemorySize(SHARED_MEMORY_BYTES)
			.withCommand("postgres",
				"-c", "shared_buffers=1GB",
				"-c", "effective_cache_size=3GB",
				"-c", "work_mem=32MB",
				"-c", "maintenance_work_mem=512MB",
				"-c", "max_wal_size=8GB",
				"-c", "synchronous_commit=off");
	}

}
