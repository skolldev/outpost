package dev.outpost.artifacts;

import java.util.Locale;

/** Debug-ID normalization shared by upload and symbolication: lowercase dashed UUID. */
public final class DebugId {

	private DebugId() {
	}

	public static String normalize(String debugId) {
		String hex = debugId.strip().toLowerCase(Locale.ROOT).replace("-", "");
		if (hex.length() != 32) {
			return debugId.strip().toLowerCase(Locale.ROOT);
		}
		return hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-" + hex.substring(12, 16) + "-"
				+ hex.substring(16, 20) + "-" + hex.substring(20);
	}
}
