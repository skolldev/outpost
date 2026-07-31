package dev.outpost.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import dev.outpost.TestcontainersConfiguration;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;

@SpringBootTest(properties = { "outpost.ingest.workers=0", "outpost.ingest.shutdown-timeout=0s",
		"outpost.ingest.spool-directory=build/test-spool/reap", "outpost.ingest.spool-max-age=1s",
		"outpost.ingest.spool-sweep-interval=100ms" })
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class SpoolReaperIntegrationTest {

	private static final Path SPOOL_DIRECTORY = Path.of("build/test-spool/reap");

	@BeforeEach
	void setUp() throws IOException {
		SpoolTestFiles.clear(SPOOL_DIRECTORY);
	}

	@Test
	void theScheduledSweepRemovesOrphansAndLogsWhatItReclaimed(CapturedOutput output) throws IOException {
		Path orphan = orphan("envelope-orphan.spool", "abc");

		awaitAbsent(orphan);

		assertThat(output).contains("reaped 1 orphaned ingest spool file, 3 bytes reclaimed");
	}

	private Path orphan(String name, String body) throws IOException {
		Path path = Files.write(SPOOL_DIRECTORY.resolve(name), body.getBytes(StandardCharsets.UTF_8));
		return Files.setLastModifiedTime(path, FileTime.from(Instant.now().minus(Duration.ofMinutes(1))));
	}

	private void awaitAbsent(Path path) {
		Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
		while (Instant.now().isBefore(deadline) && Files.exists(path)) {
			sleep();
		}
		assertThat(Files.exists(path)).isFalse();
	}

	private void sleep() {
		try {
			Thread.sleep(25);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AssertionError(e);
		}
	}
}
