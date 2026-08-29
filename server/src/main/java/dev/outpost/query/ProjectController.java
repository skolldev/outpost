package dev.outpost.query;

import dev.outpost.config.OutpostProperties;
import dev.outpost.ingest.IngestAuthenticator;
import dev.outpost.pipeline.EventIssueLock;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Project + DSN key management. Mutations are admin-only. */
@RestController
@RequestMapping("/api/internal/projects")
public class ProjectController {

	public record Project(long id, String slug, String name, String platform, Instant createdAt) {
	}

	public record Key(long id, long projectId, String publicKey, boolean isActive, Instant createdAt, String dsn) {
	}

	public record ProjectRequest(String slug, String name, String platform) {
	}

	private static final SecureRandom RANDOM = new SecureRandom();

	private final JdbcClient jdbc;
	private final OutpostProperties properties;
	private final TransactionTemplate transaction;
	private final EventIssueLock eventIssueLock;
	private final IngestAuthenticator ingestAuthenticator;

	public ProjectController(JdbcClient jdbc, OutpostProperties properties,
			PlatformTransactionManager transactionManager, EventIssueLock eventIssueLock,
			IngestAuthenticator ingestAuthenticator) {
		this.jdbc = jdbc;
		this.properties = properties;
		this.transaction = new TransactionTemplate(transactionManager);
		this.eventIssueLock = eventIssueLock;
		this.ingestAuthenticator = ingestAuthenticator;
	}

	@GetMapping
	public List<Project> list() {
		SearchQuery search = buildProjectListQuery();
		return jdbc.sql(search.sql()).params(search.params()).query(this::mapProject).list();
	}

	/**
	 * Every Project, ordered by slug — extracted per {@link SearchQuery} so the MCP
	 * Surface's {@code list_projects} Tool reads the same statement rather than a
	 * copy of it (ADR-0016). No guard accompanies it, and that is a statement about
	 * the table rather than an omission: {@code project} is a catalogue with one row
	 * per Project and no partitions, so a buffer ceiling over it could not be set
	 * anywhere it was able to fail.
	 */
	static SearchQuery buildProjectListQuery() {
		return new SearchQuery("SELECT id, slug, name, platform, created_at FROM project ORDER BY slug", List.of());
	}

	@PostMapping
	@PreAuthorize("hasRole('ADMIN')")
	public ResponseEntity<?> create(@RequestBody ProjectRequest request) {
		if (request.slug() == null || !request.slug().matches("[a-z0-9][a-z0-9-]{0,63}")) {
			return ResponseEntity.badRequest()
				.body(Map.of("detail", "slug must be lowercase alphanumeric with dashes"));
		}
		Project project = jdbc.sql("""
				INSERT INTO project (slug, name, platform) VALUES (?, ?, ?)
				RETURNING id, slug, name, platform, created_at
				""")
			.param(request.slug())
			.param(request.name() == null || request.name().isBlank() ? request.slug() : request.name())
			.param(request.platform())
			.query(this::mapProject)
			.single();
		createKey(project.id()); // a project without a DSN is useless — create the first key
		return ResponseEntity.status(HttpStatus.CREATED).body(project);
	}

	@PatchMapping("/{id}")
	@PreAuthorize("hasRole('ADMIN')")
	public ResponseEntity<Project> update(@PathVariable long id, @RequestBody ProjectRequest request) {
		return jdbc.sql("""
				UPDATE project SET name = COALESCE(?, name), platform = COALESCE(?, platform)
				WHERE id = ? RETURNING id, slug, name, platform, created_at
				""")
			.param(request.name())
			.param(request.platform())
			.param(id)
			.query(this::mapProject)
			.optional()
			.map(ResponseEntity::ok)
			.orElse(ResponseEntity.notFound().build());
	}

	@DeleteMapping("/{id}")
	@PreAuthorize("hasRole('ADMIN')")
	public ResponseEntity<Void> delete(@PathVariable long id) {
		int deleted = Objects.requireNonNull(transaction.execute(status -> deleteProject(id)));
		if (deleted > 0) {
			ingestAuthenticator.invalidateProjectKeys(id);
		}
		return deleted > 0 ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
	}

	private int deleteProject(long id) {
		eventIssueLock.acquire(id);
		// Events are partitioned and not FK-cascaded; delete them explicitly first.
		jdbc.sql("DELETE FROM event WHERE project_id = ?").param(id).update();
		return jdbc.sql("DELETE FROM project WHERE id = ?").param(id).update();
	}

	@GetMapping("/{id}/environments")
	public List<String> environments(@PathVariable long id) {
		SearchQuery search = buildEnvironmentsQuery(List.of(id));
		return jdbc.sql(search.sql()).params(search.params()).query((rs, row) -> rs.getString("name")).list();
	}

	/**
	 * The Environment Names of the given Projects, or of every Project when none are
	 * given — extracted per {@link SearchQuery} so {@code list_projects} can answer
	 * "which environments may I filter on" from the same statement this endpoint
	 * runs, in one round trip rather than one per Project.
	 *
	 * <p>Guarded by the same reasoning as {@link #buildProjectListQuery()}:
	 * {@code environment} holds one row per (Project, Environment Name) and is
	 * neither partitioned nor telemetry, so there is no honest ceiling to put on it.
	 */
	static SearchQuery buildEnvironmentsQuery(List<Long> project) {
		StringBuilder sql = new StringBuilder("SELECT project_id, name FROM environment WHERE 1=1");
		List<Object> params = new ArrayList<>();
		QuerySupport.appendInClause(sql, "project_id", project, params);
		sql.append(" ORDER BY project_id, name");
		return new SearchQuery(sql.toString(), params);
	}

	/**
	 * Intersection of Environment Names across the in-scope Projects,
	 * alphabetically sorted (ADR 0009). In-scope = the given {@code project} ids, or every Project
	 * when none are given. A Project with no {@code environment} rows is <em>no evidence</em>, not
	 * <em>evidence of absence</em>: it is excluded from the denominator rather than emptying the
	 * result, so a brand-new Project can't blank the bar.
	 */
	@GetMapping("/environments")
	public List<String> environmentIntersection(@RequestParam(required = false) List<Long> project) {
		if (project == null || project.isEmpty()) {
			return jdbc.sql("""
					SELECT name FROM environment GROUP BY name
					HAVING count(DISTINCT project_id) = (SELECT count(DISTINCT project_id) FROM environment)
					ORDER BY name
					""").query(String.class).list();
		}
		String ids = QuerySupport.placeholders(project.size());
		List<Object> params = new ArrayList<>(project);
		params.addAll(project); // bound twice: once in the outer scope, once in the denominator subquery
		return jdbc.sql("""
				SELECT name FROM environment WHERE project_id IN (%s) GROUP BY name
				HAVING count(DISTINCT project_id) =
				       (SELECT count(DISTINCT project_id) FROM environment WHERE project_id IN (%s))
				ORDER BY name
				""".formatted(ids, ids)).params(params).query(String.class).list();
	}

	@GetMapping("/{id}/keys")
	public List<Key> keys(@PathVariable long id) {
		return jdbc.sql("""
				SELECT id, project_id, public_key, is_active, created_at FROM project_key
				WHERE project_id = ? ORDER BY id
				""").param(id).query(this::mapKey).list();
	}

	@PostMapping("/{id}/keys")
	@PreAuthorize("hasRole('ADMIN')")
	public ResponseEntity<Key> addKey(@PathVariable long id) {
		return ResponseEntity.status(HttpStatus.CREATED).body(createKey(id));
	}

	@PatchMapping("/{id}/keys/{keyId}")
	@PreAuthorize("hasRole('ADMIN')")
	public ResponseEntity<Key> setKeyActive(@PathVariable long id, @PathVariable long keyId,
			@RequestBody Map<String, Boolean> body) {
		Boolean active = body.get("is_active");
		if (active == null) {
			return ResponseEntity.badRequest().build();
		}
		var updated = jdbc.sql("""
				UPDATE project_key SET is_active = ? WHERE id = ? AND project_id = ?
				RETURNING id, project_id, public_key, is_active, created_at
				""")
			.param(active)
			.param(keyId)
			.param(id)
			.query(this::mapKey)
			.optional();
		if (updated.isPresent()) {
			ingestAuthenticator.invalidateProjectKeys(id);
		}
		return updated
			.map(ResponseEntity::ok)
			.orElse(ResponseEntity.notFound().build());
	}

	private Key createKey(long projectId) {
		byte[] bytes = new byte[16];
		RANDOM.nextBytes(bytes);
		Key key = jdbc.sql("""
				INSERT INTO project_key (project_id, public_key) VALUES (?, ?)
				RETURNING id, project_id, public_key, is_active, created_at
				""").param(projectId).param(HexFormat.of().formatHex(bytes)).query(this::mapKey).single();
		ingestAuthenticator.invalidateProjectKeys(projectId);
		return key;
	}

	private Project mapProject(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
		return new Project(rs.getLong("id"), rs.getString("slug"), rs.getString("name"), rs.getString("platform"),
				rs.getTimestamp("created_at").toInstant());
	}

	private Key mapKey(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
		long projectId = rs.getLong("project_id");
		String publicKey = rs.getString("public_key");
		return new Key(rs.getLong("id"), projectId, publicKey, rs.getBoolean("is_active"),
				rs.getTimestamp("created_at").toInstant(), properties.dsn(publicKey, projectId));
	}
}
