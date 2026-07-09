package dev.outpost.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class FingerprinterTest {

	private final ObjectMapper mapper = new ObjectMapper();

	@Test
	void identicalExceptionsGroupTogether() {
		assertThat(fp(exceptionEvent("NPE", "user was null at 12:01", "dev.demo.A", "handle")))
			.isEqualTo(fp(exceptionEvent("NPE", "user was null at 09:30", "dev.demo.A", "handle")));
	}

	@Test
	void differentTypeOrFramesGroupApart() {
		String base = fp(exceptionEvent("NPE", "x", "dev.demo.A", "handle"));
		assertThat(fp(exceptionEvent("ISE", "x", "dev.demo.A", "handle"))).isNotEqualTo(base);
		assertThat(fp(exceptionEvent("NPE", "x", "dev.demo.B", "handle"))).isNotEqualTo(base);
	}

	@Test
	void lambdaIndicesAndProxySuffixesAreNormalized() {
		String a = fp(exceptionEvent("NPE", "x", "dev.demo.A$$Lambda$17/0x0000abc", "lambda$handle$3"));
		String b = fp(exceptionEvent("NPE", "x", "dev.demo.A$$Lambda$99/0x0000def", "lambda$handle$7"));
		assertThat(a).isEqualTo(b);

		String c = fp(exceptionEvent("NPE", "x", "dev.demo.A$$EnhancerBySpringCGLIB$$ab12cd34", "run"));
		String d = fp(exceptionEvent("NPE", "x", "dev.demo.A$$EnhancerBySpringCGLIB$$ffee0011", "run"));
		assertThat(c).isEqualTo(d);
	}

	@Test
	void sdkFingerprintWinsAndSupportsDefaultSubstitution() {
		JsonNode custom = json("""
				{"fingerprint":["my-group"],"exception":{"values":[{"type":"A","stacktrace":{"frames":[
				{"module":"m1","function":"f1","in_app":true}]}}]}}""");
		JsonNode differentStack = json("""
				{"fingerprint":["my-group"],"exception":{"values":[{"type":"B","stacktrace":{"frames":[
				{"module":"m2","function":"f2","in_app":true}]}}]}}""");
		assertThat(Fingerprinter.fingerprint(custom)).isEqualTo(Fingerprinter.fingerprint(differentStack));

		JsonNode withDefault = json("""
				{"fingerprint":["{{ default }}","tenant-a"],"exception":{"values":[{"type":"A","stacktrace":{"frames":[
				{"module":"m1","function":"f1","in_app":true}]}}]}}""");
		assertThat(Fingerprinter.fingerprint(withDefault)).isNotEqualTo(Fingerprinter.fingerprint(custom));
	}

	@Test
	void messageFallbackMasksVariableData() {
		assertThat(fp(messageEvent("Timeout after 5000ms for request 550e8400-e29b-41d4-a716-446655440000")))
			.isEqualTo(fp(messageEvent("Timeout after 3000ms for request 123e4567-e89b-42d3-a456-426614174000")));
		assertThat(fp(messageEvent("Timeout after 5000ms"))).isNotEqualTo(fp(messageEvent("Connection refused")));
	}

	private String fp(JsonNode event) {
		return Fingerprinter.fingerprint(event);
	}

	private JsonNode messageEvent(String message) {
		return json("{\"message\":\"" + message + "\"}");
	}

	private JsonNode exceptionEvent(String type, String value, String module, String function) {
		return json("""
				{"exception":{"values":[{"type":"%s","value":"%s","stacktrace":{"frames":[
				{"module":"%s","function":"%s","in_app":true},
				{"module":"org.vendor.Lib","function":"call","in_app":false}
				]}}]}}""".formatted(type, value, module, function));
	}

	private JsonNode json(String text) {
		return mapper.readTree(text);
	}
}
