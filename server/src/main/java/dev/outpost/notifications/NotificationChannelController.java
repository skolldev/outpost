package dev.outpost.notifications;

import java.net.URI;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
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

/**
 * Notification Channel management (§ issue #42, parent #41): admin-configured
 * webhook destinations. This slice is configuration only — no delivery yet.
 * <p>
 * The whole surface is Admin-only, unlike uptime reads: a channel's URL is a
 * bearer credential returned unmasked (ADR 0006), so even listing is gated. An
 * empty {@code project_filter} matches all Projects and an empty
 * {@code environment_filter} matches all Environments; the eventual publisher
 * applies these, this controller only persists them.
 */
@RestController
@RequestMapping("/api/internal/notifications/channels")
@PreAuthorize("hasRole('ADMIN')")
public class NotificationChannelController {

	private static final Set<String> TYPES = Set.of("teams", "generic_json");
	private static final Set<String> TRIGGERS = Set.of("new_issue", "incident_started", "incident_resolved");

	public record ChannelRequest(String name, String type, String url, Boolean enabled, List<String> triggers,
			List<Long> projectFilter, List<String> environmentFilter) {
	}

	public record Channel(long id, String name, String type, String url, boolean enabled, List<String> triggers,
			List<Long> projectFilter, List<String> environmentFilter, Instant createdAt) {
	}

	private final JdbcClient jdbc;

	public NotificationChannelController(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@GetMapping
	public List<Channel> list() {
		return jdbc.sql("""
				SELECT id, name, type, url, enabled, triggers, project_filter, environment_filter, created_at
				FROM notification_channel ORDER BY name, id
				""").query(this::mapChannel).list();
	}

	@PostMapping
	public ResponseEntity<?> create(@RequestBody ChannelRequest request) {
		String problem = validate(request);
		if (problem != null) {
			return ResponseEntity.badRequest().body(Map.of("detail", problem));
		}
		long id = jdbc.sql("""
				INSERT INTO notification_channel
					(name, type, url, enabled, triggers, project_filter, environment_filter)
				VALUES (?, ?, ?, ?, string_to_array(?, ','), string_to_array(?, ',')::bigint[], string_to_array(?, ','))
				RETURNING id
				""")
			.param(request.name().trim())
			.param(request.type())
			.param(request.url().trim())
			.param(enabledOrDefault(request))
			.param(String.join(",", triggers(request)))
			.param(joinIds(projectFilter(request)))
			.param(String.join(",", environmentFilter(request)))
			.query(Long.class)
			.single();
		return ResponseEntity.status(HttpStatus.CREATED).body(get(id));
	}

	@PatchMapping("/{id}")
	public ResponseEntity<?> update(@PathVariable long id, @RequestBody ChannelRequest request) {
		String problem = validate(request);
		if (problem != null) {
			return ResponseEntity.badRequest().body(Map.of("detail", problem));
		}
		// Full replace (like uptime edits): the UI resends the whole channel,
		// including for the enable/disable toggle, so there is no partial state.
		int updated = jdbc.sql("""
				UPDATE notification_channel SET
					name = ?, type = ?, url = ?, enabled = ?, triggers = string_to_array(?, ','),
					project_filter = string_to_array(?, ',')::bigint[], environment_filter = string_to_array(?, ',')
				WHERE id = ?
				""")
			.param(request.name().trim())
			.param(request.type())
			.param(request.url().trim())
			.param(enabledOrDefault(request))
			.param(String.join(",", triggers(request)))
			.param(joinIds(projectFilter(request)))
			.param(String.join(",", environmentFilter(request)))
			.param(id)
			.update();
		return updated > 0 ? ResponseEntity.ok(get(id)) : ResponseEntity.notFound().build();
	}

	@DeleteMapping("/{id}")
	public ResponseEntity<Void> delete(@PathVariable long id) {
		int deleted = jdbc.sql("DELETE FROM notification_channel WHERE id = ?").param(id).update();
		return deleted > 0 ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
	}

	private Channel get(long id) {
		return jdbc.sql("""
				SELECT id, name, type, url, enabled, triggers, project_filter, environment_filter, created_at
				FROM notification_channel WHERE id = ?
				""").param(id).query(this::mapChannel).single();
	}

	private String validate(ChannelRequest request) {
		if (request.name() == null || request.name().isBlank()) {
			return "name is required";
		}
		if (request.type() == null || !TYPES.contains(request.type())) {
			return "type must be one of teams, generic_json";
		}
		String urlProblem = validateUrl(request.url());
		if (urlProblem != null) {
			return urlProblem;
		}
		List<String> triggers = triggers(request);
		if (triggers.isEmpty()) {
			return "at least one trigger is required";
		}
		for (String trigger : triggers) {
			if (!TRIGGERS.contains(trigger)) {
				return "unknown trigger: " + trigger;
			}
		}
		return null;
	}

	/**
	 * Well-formedness only (ADR 0006): no egress/SSRF filtering, so localhost,
	 * private ranges, and internal hostnames are all accepted.
	 */
	private String validateUrl(String url) {
		if (url == null || url.isBlank()) {
			return "url is required";
		}
		URI uri;
		try {
			uri = URI.create(url.trim());
		}
		catch (IllegalArgumentException e) {
			return "url is not a valid URL";
		}
		String scheme = uri.getScheme();
		if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
			return "url must be an http or https URL";
		}
		if (uri.getHost() == null || uri.getHost().isBlank()) {
			return "url must include a host";
		}
		return null;
	}

	private boolean enabledOrDefault(ChannelRequest request) {
		return request.enabled() == null || request.enabled();
	}

	private List<String> triggers(ChannelRequest request) {
		return request.triggers() == null ? List.of() : request.triggers();
	}

	private List<Long> projectFilter(ChannelRequest request) {
		return request.projectFilter() == null ? List.of() : request.projectFilter();
	}

	private List<String> environmentFilter(ChannelRequest request) {
		if (request.environmentFilter() == null) {
			return List.of();
		}
		return request.environmentFilter()
			.stream()
			.filter(env -> env != null && !env.isBlank())
			.map(String::trim)
			.distinct()
			.toList();
	}

	private String joinIds(List<Long> ids) {
		return ids.stream().map(String::valueOf).collect(Collectors.joining(","));
	}

	private Channel mapChannel(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
		List<String> triggers = List.of((String[]) rs.getArray("triggers").getArray());
		List<Long> projectFilter = Arrays.asList((Long[]) rs.getArray("project_filter").getArray());
		List<String> environmentFilter = List.of((String[]) rs.getArray("environment_filter").getArray());
		return new Channel(rs.getLong("id"), rs.getString("name"), rs.getString("type"), rs.getString("url"),
				rs.getBoolean("enabled"), triggers, projectFilter, environmentFilter,
				rs.getTimestamp("created_at").toInstant());
	}
}
