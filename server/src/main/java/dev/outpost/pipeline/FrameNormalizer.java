package dev.outpost.pipeline;

/**
 * Normalizes stack-frame identity for grouping (§6.2): strips the parts that
 * vary between builds or JVM runs — lambda indices, proxy/CGLIB suffixes,
 * webpack hash fragments — so logically identical frames fingerprint equally.
 */
final class FrameNormalizer {

	private FrameNormalizer() {
	}

	/** Normalizes a Java module / JS filename for fingerprinting. */
	static String normalizeModule(String module) {
		if (module == null) {
			return "";
		}
		String m = module;
		// JVM synthetic lambda classes: Foo$$Lambda$123/0x0000 → Foo
		m = m.replaceAll("\\$\\$Lambda\\$?\\d*(/0x[0-9a-fA-F]+|/\\d+)?", "");
		// JDK/CGLIB proxies: com.sun.proxy.$Proxy12, Foo$$EnhancerBySpringCGLIB$$ab12cd34,
		// Foo$$FastClassBySpringCGLIB$$ab12cd34, Foo$$SpringCGLIB$$0
		m = m.replaceAll("\\$Proxy\\d+", "\\$Proxy");
		m = m.replaceAll("\\$\\$[A-Za-z]*CGLIB\\$\\$[0-9a-fA-F]+", "");
		// Webpack/Angular CLI content hashes: main-ABC123DEF.js, chunk-X7K2P.mjs, main.abc123def0.js
		m = m.replaceAll("[-.][0-9a-fA-F]{8,}(?=\\.m?js$)", "");
		m = m.replaceAll("(chunk|main|polyfills|vendor|runtime)[-.][0-9A-Za-z_]{5,}(?=\\.m?js$)", "$1");
		return m;
	}

	/** Normalizes a function name for fingerprinting. */
	static String normalizeFunction(String function) {
		if (function == null) {
			return "";
		}
		String f = function;
		// lambda$handle$3 → lambda$handle
		f = f.replaceAll("(lambda\\$[^$]+)\\$\\d+", "$1");
		// anonymous lambda method names: lambda$3 → lambda
		f = f.replaceAll("lambda\\$\\d+", "lambda");
		return f;
	}
}
