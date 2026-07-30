package dev.outpost.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.outpost.config.OutpostProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.zip.ZipException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class EnvelopeControllerTest {

	private final EnvelopeParser parser = mock(EnvelopeParser.class);
	private final EnvelopeSpool spool = mock(EnvelopeSpool.class);
	private final IngestAuthenticator authenticator = mock(IngestAuthenticator.class);
	private final IngestQueue queue = mock(IngestQueue.class);
	private final IngestMetrics metrics = mock(IngestMetrics.class);
	private final OutpostProperties properties = mock(OutpostProperties.class);
	private final ClientReportCounters clientReports = mock(ClientReportCounters.class);
	private final EnvelopeController controller =
			new EnvelopeController(parser, spool, authenticator, queue, clientReports, metrics, properties);

	@Test
	void spoolReadFailureIsReportedAsAnInternalError() throws IOException {
		HttpServletRequest request = mock(HttpServletRequest.class);
		SpoolFile file = new SpoolFile(Path.of("missing.spool"), false);
		when(spool.write(request)).thenReturn(file);
		when(spool.open(file)).thenThrow(new IOException("disk read failed"));

		assertThatThrownBy(() -> controller.envelope(42, null, request))
			.isInstanceOf(EnvelopeSpool.SpoolReadException.class)
			.hasCauseInstanceOf(IOException.class);
		assertThat(controller.spoolReadFailure().getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
		verify(spool).delete(file);
	}

	@Test
	void invalidGzipIsStillReportedAsMalformed() throws IOException {
		HttpServletRequest request = mock(HttpServletRequest.class);
		SpoolFile file = new SpoolFile(Path.of("invalid.spool"), true);
		when(spool.write(request)).thenReturn(file);
		when(spool.open(file)).thenThrow(new ZipException("invalid gzip"));

		assertThatThrownBy(() -> controller.envelope(42, null, request))
			.isInstanceOf(EnvelopeParser.MalformedEnvelopeException.class)
			.hasMessage("invalid gzip body");
		verify(spool).delete(file);
	}

	@Test
	void uncompressedEofIsStillReportedAsAnInternalError() throws IOException {
		HttpServletRequest request = mock(HttpServletRequest.class);
		SpoolFile file = new SpoolFile(Path.of("truncated.spool"), false);
		when(spool.write(request)).thenReturn(file);
		when(spool.open(file)).thenThrow(new EOFException("disk read failed"));

		assertThatThrownBy(() -> controller.envelope(42, null, request))
			.isInstanceOf(EnvelopeSpool.SpoolReadException.class)
			.hasCauseInstanceOf(EOFException.class);
		verify(spool).delete(file);
	}
}
