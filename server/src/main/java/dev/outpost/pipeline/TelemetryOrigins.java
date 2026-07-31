package dev.outpost.pipeline;

import java.util.Collection;
import java.util.Comparator;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Auto-creates the environments and releases a batch of telemetry says it came
 * from — the ingest-side half of "environments and releases are never managed
 * by hand".
 *
 * <p>Costs one round trip per <em>distinct</em> value, not per record (#107). A
 * batch is up to 50,000 rows sharing a handful of environments and one release,
 * so the per-row form spent almost all of its round trips on {@code DO NOTHING}
 * — inside the storing transaction, and for events inside the per-project
 * advisory lock.
 *
 * <p>Callers upsert inside their own storing transaction, so an environment is
 * never created for rows that roll back.
 */
@Component
public class TelemetryOrigins {

	private static final String ENVIRONMENT_UPSERT = """
			INSERT INTO environment (project_id, name) VALUES (?, ?)
			ON CONFLICT (project_id, name) DO NOTHING
			""";

	private static final String RELEASE_UPSERT = """
			INSERT INTO release (project_id, version) VALUES (?, ?)
			ON CONFLICT (project_id, version) DO NOTHING
			""";

	/** A project-scoped name — an environment's or a release's. */
	private record Ref(long projectId, String name) {
	}

	private static final Comparator<Ref> ORDER = Comparator.comparingLong(Ref::projectId).thenComparing(Ref::name);

	private final JdbcTemplate jdbc;

	public TelemetryOrigins(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/** Creates any environment or release in {@code batch} that does not exist yet. */
	public void ensure(Collection<? extends ProcessedTelemetry> batch) {
		for (Ref environment : distinct(batch, ProcessedTelemetry::environment)) {
			jdbc.update(ENVIRONMENT_UPSERT, environment.projectId(), environment.name());
		}
		for (Ref release : distinct(batch, ProcessedTelemetry::release)) {
			jdbc.update(RELEASE_UPSERT, release.projectId(), release.name());
		}
	}

	/**
	 * The distinct project-scoped values of one field, in a stable order so two
	 * concurrent batches inserting the same pair of environments take them in the
	 * same order and cannot deadlock on each other.
	 *
	 * <p>Only a release is ever null — the pipelines default an absent environment
	 * to {@code production}, and the signal tables reject a null one anyway, so
	 * skipping it here cannot hide a bad row.
	 */
	private static Set<Ref> distinct(Collection<? extends ProcessedTelemetry> batch,
			Function<ProcessedTelemetry, String> field) {
		Set<Ref> refs = new TreeSet<>(ORDER);
		for (ProcessedTelemetry record : batch) {
			String name = field.apply(record);
			if (name != null) {
				refs.add(new Ref(record.projectId(), name));
			}
		}
		return refs;
	}
}
