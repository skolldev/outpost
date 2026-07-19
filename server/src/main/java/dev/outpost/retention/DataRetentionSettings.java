package dev.outpost.retention;

import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Persists the installation-wide, opt-in telemetry retention policy. */
@Service
public class DataRetentionSettings {

	public record Policy(boolean enabled, int retentionDays) {
	}

	static final String ENABLED_KEY = "data_retention_enabled";
	static final String DAYS_KEY = "data_retention_days";
	public static final Policy DEFAULT = new Policy(false, 90);

	private final JdbcClient jdbc;
	private final TransactionTemplate transaction;

	public DataRetentionSettings(JdbcClient jdbc, PlatformTransactionManager transactionManager) {
		this.jdbc = jdbc;
		this.transaction = new TransactionTemplate(transactionManager);
	}

	public Policy get() {
		Map<String, String> values = jdbc.sql("SELECT key, value FROM setting WHERE key IN (?, ?)")
			.param(ENABLED_KEY)
			.param(DAYS_KEY)
			.query((rs, rowNum) -> Map.entry(rs.getString("key"), rs.getString("value")))
			.list()
			.stream()
			.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
		return new Policy(Boolean.parseBoolean(values.getOrDefault(ENABLED_KEY, "false")),
				validDays(values.get(DAYS_KEY)));
	}

	public Policy save(boolean enabled, int retentionDays) {
		if (!isSupported(retentionDays)) {
			throw new IllegalArgumentException("retention_days must be one of 30, 60, or 90");
		}
		transaction.executeWithoutResult(status -> {
			upsert(ENABLED_KEY, Boolean.toString(enabled));
			upsert(DAYS_KEY, Integer.toString(retentionDays));
		});
		return new Policy(enabled, retentionDays);
	}

	public static boolean isSupported(int retentionDays) {
		return retentionDays == 30 || retentionDays == 60 || retentionDays == 90;
	}

	private int validDays(String value) {
		try {
			int days = Integer.parseInt(value);
			return isSupported(days) ? days : DEFAULT.retentionDays();
		}
		catch (NumberFormatException e) {
			return DEFAULT.retentionDays();
		}
	}

	private void upsert(String key, String value) {
		jdbc.sql("""
				INSERT INTO setting (key, value) VALUES (?, ?)
				ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = now()
				""").param(key).param(value).update();
	}
}
