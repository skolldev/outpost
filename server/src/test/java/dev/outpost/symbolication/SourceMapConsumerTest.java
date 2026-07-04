package dev.outpost.symbolication;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Validates the consumer against fixtures generated from a real Angular 22
 * production build (`ng build --source-map`), with expected lookups produced
 * by Node's built-in {@code node:module} SourceMap (Chrome DevTools port) —
 * see the §13.2 risk spike. Regenerate with gen-fixtures.js (next to the
 * fixtures) if they ever need refreshing.
 */
class SourceMapConsumerTest {

	private final ObjectMapper mapper = new ObjectMapper();

	@Test
	void matchesNodeReferenceOnRealAngularMap() {
		verifyProbes("angular-chunk");
	}

	@Test
	void matchesNodeReferenceOnIndexMap() {
		verifyProbes("index-map");
	}

	@Test
	void exposesSourcesContentForContextLines() {
		SourceMapConsumer consumer = SourceMapConsumer.parse(resource("/sourcemap/angular-chunk.js.map"));
		SourceMapConsumer.OriginalPosition position = consumer.find(2, 20);
		assertThat(position.source()).isEqualTo("src/app/pages/issue-detail.ts");
		assertThat(position.sourceContent()).contains("issue-detail");
	}

	private void verifyProbes(String fixture) {
		SourceMapConsumer consumer = SourceMapConsumer.parse(resource("/sourcemap/" + fixture + ".js.map"));
		JsonNode probes = resource("/sourcemap/" + fixture + ".expected.json");
		assertThat(probes).isNotEmpty();
		for (JsonNode probe : probes) {
			SourceMapConsumer.OriginalPosition position = consumer.find(probe.get("generatedLine").asInt(),
					probe.get("generatedColumn").asInt());
			String at = " at " + probe.get("generatedLine") + ":" + probe.get("generatedColumn");
			if (probe.get("originalSource").isNull()) {
				assertThat(position).as("no mapping expected" + at).isNull();
			}
			else {
				assertThat(position).as("mapping expected" + at).isNotNull();
				assertThat(position.source()).as("source" + at).isEqualTo(probe.get("originalSource").asText());
				assertThat(position.line()).as("line" + at).isEqualTo(probe.get("originalLine").asInt());
				assertThat(position.column()).as("column" + at).isEqualTo(probe.get("originalColumn").asInt());
				assertThat(position.name()).as("name" + at).isEqualTo(probe.get("name").asText(null));
			}
		}
	}

	private JsonNode resource(String path) {
		try (InputStream in = getClass().getResourceAsStream(path)) {
			return mapper.readTree(in);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
