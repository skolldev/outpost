package dev.outpost.query;

import dev.outpost.config.OutpostProperties;
import java.net.URI;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Project + DSN key management (§8). Mutations are admin-only. */
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

	public ProjectController(JdbcClient jdbc, OutpostProperties properties) {
		this.jdbc = jdbc;
		this.properties = properties;
	}

	@GetMapping
	public List<Project> list() {
		return jdbc.sql("SELECT id, slug, name, platform, created_at FROM project ORDER BY slug")
			.query(this::mapProject)
			.list();
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
		// Events are partitioned and not FK-cascaded; delete them explicitly first.
		jdbc.sql("DELETE FROM event WHERE project_id = ?").param(id).update();
		int deleted = jdbc.sql("DELETE FROM project WHERE id = ?").param(id).update();
		return deleted > 0 ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
	}

	@GetMapping("/{id}/environments")
	public List<String> environments(@PathVariable long id) {
		return jdbc.sql("SELECT name FROM environment WHERE project_id = ? ORDER BY name")
			.param(id)
			.query(String.class)
			.list();
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
		return jdbc.sql("""
				UPDATE project_key SET is_active = ? WHERE id = ? AND project_id = ?
				RETURNING id, project_id, public_key, is_active, created_at
				""")
			.param(active)
			.param(keyId)
			.param(id)
			.query(this::mapKey)
			.optional()
			.map(ResponseEntity::ok)
			.orElse(ResponseEntity.notFound().build());
	}

	private Key createKey(long projectId) {
		byte[] bytes = new byte[16];
		RANDOM.nextBytes(bytes);
		return jdbc.sql("""
				INSERT INTO project_key (project_id, public_key) VALUES (?, ?)
				RETURNING id, project_id, public_key, is_active, created_at
				""").param(projectId).param(HexFormat.of().formatHex(bytes)).query(this::mapKey).single();
	}

	private Project mapProject(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
		return new Project(rs.getLong("id"), rs.getString("slug"), rs.getString("name"), rs.getString("platform"),
				rs.getTimestamp("created_at").toInstant());
	}

	private Key mapKey(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
		long projectId = rs.getLong("project_id");
		String publicKey = rs.getString("public_key");
		return new Key(rs.getLong("id"), projectId, publicKey, rs.getBoolean("is_active"),
				rs.getTimestamp("created_at").toInstant(), dsn(publicKey, projectId));
	}

	/** DSN per §4.1: {@code scheme://<public_key>@<host>/<project_id>}. */
	private String dsn(String publicKey, long projectId) {
		URI base = URI.create(properties.publicUrl());
		String authority = base.getPort() > 0 ? base.getHost() + ":" + base.getPort() : base.getHost();
		return base.getScheme() + "://" + publicKey + "@" + authority + "/" + projectId;
	}
}
