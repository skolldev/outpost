package dev.outpost.query;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;

/**
 * Shared query-controller helpers: SQL {@code IN}-clause placeholders and the
 * base64url keyset-pagination cursor codec. Extracted when the third query
 * controller (traces) would otherwise have copied them a third time; the older
 * {@link LogController} / IssueController keep their private copies.
 */
final class QuerySupport {

	private QuerySupport() {
	}

	/** {@code n} comma-separated {@code ?} placeholders for a SQL {@code IN (...)} list. */
	static String placeholders(int n) {
		return String.join(",", Collections.nCopies(n, "?"));
	}

	/** Encodes a keyset cursor as base64url of {@code sortValue|id}. */
	static String encodeCursor(String sortValue, String id) {
		return Base64.getUrlEncoder().withoutPadding()
			.encodeToString((sortValue + "|" + id).getBytes(StandardCharsets.UTF_8));
	}

	/** Decodes a cursor into its {@code [sortValue, id]} parts; throws on a malformed cursor. */
	static String[] decodeCursor(String cursor) {
		String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
		String[] parts = decoded.split("\\|", 2);
		if (parts.length != 2) {
			throw new IllegalArgumentException("invalid cursor");
		}
		return parts;
	}
}
