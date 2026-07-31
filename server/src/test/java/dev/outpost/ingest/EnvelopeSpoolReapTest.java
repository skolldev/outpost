package dev.outpost.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;

class EnvelopeSpoolReapTest {

	private static final Duration MAX_AGE = Duration.ofMinutes(30);

	@TempDir
	Path directory;

	private EnvelopeSpool spool() {
		return new EnvelopeSpool(directory, 4194304, 20971520);
	}

	@Test
	void reapsAnAgedFileWithNoLiveQueueEntry() throws IOException {
		EnvelopeSpool spool = spool();
		Path orphan = age(write("{}\n"), Duration.ofHours(2));

		EnvelopeSpool.Sweep sweep = spool.reap(MAX_AGE);

		assertThat(Files.exists(orphan)).isFalse();
		assertThat(sweep.files()).isEqualTo(1);
		assertThat(sweep.bytes()).isEqualTo(3);
	}

	@Test
	void leavesAnOrphanYoungerThanTheThreshold() throws IOException {
		EnvelopeSpool spool = spool();
		Path fresh = write("{}\n");

		EnvelopeSpool.Sweep sweep = spool.reap(MAX_AGE);

		assertThat(Files.exists(fresh)).isTrue();
		assertThat(sweep.files()).isZero();
		assertThat(sweep.bytes()).isZero();
	}

	@Test
	void leavesAnInFlightFileEvenWhenItIsOlderThanTheThreshold() throws IOException {
		EnvelopeSpool spool = spool();
		SpoolFile inFlight = spool.write(request("{}\n"));
		age(inFlight.path(), Duration.ofHours(2));

		EnvelopeSpool.Sweep sweep = spool.reap(MAX_AGE);

		assertThat(Files.exists(inFlight.path())).isTrue();
		assertThat(sweep.files()).isZero();
	}

	@Test
	void reapsAFileOnceItsQueueEntryIsGone() throws IOException {
		EnvelopeSpool spool = spool();
		SpoolFile digested = spool.write(request("{}\n"));
		spool.delete(digested);
		Path residue = age(write("{}\n"), Duration.ofHours(2));

		EnvelopeSpool.Sweep sweep = spool.reap(MAX_AGE);

		assertThat(Files.exists(residue)).isFalse();
		assertThat(sweep.files()).isEqualTo(1);
	}

	@Test
	void sumsFilesAndBytesAcrossTheSweep() throws IOException {
		EnvelopeSpool spool = spool();
		age(write("aaaa"), Duration.ofHours(2));
		age(write("bbbbbb"), Duration.ofHours(2));
		write("kept-because-fresh");

		EnvelopeSpool.Sweep sweep = spool.reap(MAX_AGE);

		assertThat(sweep.files()).isEqualTo(2);
		assertThat(sweep.bytes()).isEqualTo(10);
		assertThat(SpoolTestFiles.count(directory)).isEqualTo(1);
	}

	@Test
	void sweepingAnAbsentDirectoryIsANoop() throws IOException {
		EnvelopeSpool spool = new EnvelopeSpool(directory.resolve("never-created"), 4194304, 20971520);

		EnvelopeSpool.Sweep sweep = spool.reap(MAX_AGE);

		assertThat(sweep.isEmpty()).isTrue();
	}

	private Path write(String body) throws IOException {
		return Files.write(Files.createTempFile(directory, "envelope-", ".spool"),
				body.getBytes(StandardCharsets.UTF_8));
	}

	private Path age(Path path, Duration by) throws IOException {
		return Files.setLastModifiedTime(path, FileTime.from(Instant.now().minus(by)));
	}

	private MockHttpServletRequest request(String body) {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setContent(body.getBytes(StandardCharsets.UTF_8));
		return request;
	}
}
