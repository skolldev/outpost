package dev.outpost.bench;

import dev.outpost.TestcontainersConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A Postgres tuned for the retrieval benchmark, replacing the defaults
 * {@code TestcontainersConfiguration} leaves alone (notably Postgres's own
 * {@code shared_buffers=128MB}, which would make a multi-gigabyte dataset read
 * as cold I/O rather than the tuned condition being measured).
 * {@code synchronous_commit=off} and the raised {@code maintenance_work_mem} are
 * for the seed only and don't affect the read-only benchmark.
 */
@TestConfiguration(proxyBeanMethods = false)
public class BenchContainerConfiguration {

	/**
	 * Docker's 64 MB default for {@code /dev/shm} is too small for a parallel hash
	 * or sort over millions of rows; running out surfaces as {@code could not resize
	 * shared memory segment}.
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
