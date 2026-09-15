package dev.outpost.query;

import dev.outpost.pipeline.ProcessedLog;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * One {@code attr=} filter on the Log Record stream: {@code key=value} for
 * equality, a bare {@code key} for presence.
 *
 * <p>The one place equality is defined, because it is defined twice over — once
 * as SQL for the list, the timeline and {@code search_logs}, once in-process for
 * the live tail — and the two must agree on every record (ADR-0018). The filter
 * is text; an attribute holds whatever JSON type the SDK sent. Equality is
 * therefore textual, and served by containment: the text is expanded into each
 * scalar JSON value it could denote, and a record matches when its attribute
 * equals any of them. Containment, unlike {@code attributes->>key}, is what
 * {@code idx_log_attributes} indexes (#132).
 *
 * @param value null for a presence filter
 * @param candidates the scalar JSON values {@code value} could denote, derived once
 * rather than per record because the live tail tests every streamed record against them
 */
record LogAttributeFilter(String key, @Nullable String value, List<JsonNode> candidates) {

	/** A JSON number, exactly as the JSON grammar spells one — {@code 03} and {@code +3} are strings. */
	private static final Pattern JSON_NUMBER = Pattern.compile("-?(0|[1-9]\\d*)(\\.\\d+)?([eE][+-]?\\d+)?");

	/** Postgres {@code numeric}'s limits on digits before and after the decimal point. */
	private static final int NUMERIC_MAX_INTEGER_DIGITS = 131_072;

	private static final int NUMERIC_MAX_FRACTION_DIGITS = 16_383;

	static List<LogAttributeFilter> parse(List<String> attr) {
		if (attr == null) {
			return List.of();
		}
		return attr.stream().filter(a -> a != null && !a.isBlank()).map(a -> {
			int eq = a.indexOf('=');
			return eq < 0 ? of(a, null) : of(a.substring(0, eq), a.substring(eq + 1));
		}).toList();
	}

	private static LogAttributeFilter of(String key, @Nullable String value) {
		return new LogAttributeFilter(key, value, value == null ? List.of() : candidates(value));
	}

	void appendSql(StringBuilder sql, List<Object> params) {
		if (value == null) {
			sql.append(" AND jsonb_exists(attributes, ?)");
			params.add(key);
			return;
		}
		sql.append(" AND (");
		for (int i = 0; i < candidates.size(); i++) {
			sql.append(i == 0 ? "" : " OR ").append("attributes @> ?::jsonb");
			params.add(JsonNodeFactory.instance.objectNode().set(key, candidates.get(i)).toString());
		}
		sql.append(")");
	}

	boolean matches(ProcessedLog record) {
		JsonNode attribute = record.attributes().get(key);
		if (value == null) {
			return attribute != null && !attribute.isNull();
		}
		return attribute != null && candidates.stream().anyMatch(candidate -> sameScalar(attribute, candidate));
	}

	/** Every scalar JSON value the filter text could denote: always the string, plus a number or boolean it spells. */
	private static List<JsonNode> candidates(String value) {
		List<JsonNode> candidates = new ArrayList<>();
		BigDecimal number = storableNumber(value);
		if (number != null) {
			candidates.add(JsonNodeFactory.instance.numberNode(number));
		}
		else if (value.equals("true") || value.equals("false")) {
			candidates.add(JsonNodeFactory.instance.booleanNode(Boolean.parseBoolean(value)));
		}
		candidates.add(JsonNodeFactory.instance.stringNode(value));
		return List.copyOf(candidates);
	}

	/**
	 * The number {@code value} spells, if a jsonb attribute could hold it. {@code 1e200000}
	 * is spelled like a number, but no stored attribute can have that value — binding it
	 * would fail the whole query on {@code numeric} overflow, and {@code 1e9999999999}
	 * fails {@link BigDecimal} before that — so it is matched as the text it is.
	 */
	private static @Nullable BigDecimal storableNumber(String value) {
		if (!JSON_NUMBER.matcher(value).matches()) {
			return null;
		}
		BigDecimal number;
		try {
			number = new BigDecimal(value);
		}
		catch (NumberFormatException e) {
			return null;
		}
		// long arithmetic: precision and scale are each an int, their difference need not be.
		long integerDigits = (long) number.precision() - number.scale();
		return integerDigits <= NUMERIC_MAX_INTEGER_DIGITS && number.scale() <= NUMERIC_MAX_FRACTION_DIGITS ? number
				: null;
	}

	/** Equality as jsonb containment decides it for a scalar: numbers by numeric value, the rest exactly. */
	private static boolean sameScalar(JsonNode attribute, JsonNode candidate) {
		if (attribute.isNumber() && candidate.isNumber()) {
			return attribute.decimalValue().compareTo(candidate.decimalValue()) == 0;
		}
		return attribute.getNodeType() == candidate.getNodeType() && attribute.equals(candidate);
	}
}
