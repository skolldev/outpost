package dev.outpost.ingest;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import tools.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class EnvelopeParserTest {

	private final EnvelopeParser parser = new EnvelopeParser(new ObjectMapper());

	@Test
	void rejectsFractionalItemLength() {
		assertMalformedLength("1.5");
	}

	@Test
	void rejectsItemLengthOutsideTheLongRange() {
		assertMalformedLength("9223372036854775808");
	}

	private void assertMalformedLength(String length) {
		String envelope = "{}\n{\"type\":\"event\",\"length\":" + length + "}\n{}";

		assertThatThrownBy(() -> parser.parse(
				new ByteArrayInputStream(envelope.getBytes(StandardCharsets.UTF_8)), item -> {
				}))
			.isInstanceOf(EnvelopeParser.MalformedEnvelopeException.class)
			.hasMessage("item length out of bounds");
	}
}
