package dev.outpost.query;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The MCP Surface's {@code list_projects} Tool: the Projects this installation
 * holds, each with the Environment Names telemetry has arrived under and its
 * most recently created release versions. Call this first — every other Tool
 * names a Project by slug, an Environment by exact name and a release by exact
 * version, none of which can be guessed.
 */
@Component
public class ProjectListTool {

	public record ProjectListResult(List<ProjectPayload> projects, List<String> caveats) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ProjectPayload(long id, String slug, String name, @Nullable String platform,
			List<String> environments, List<String> recent_releases) {
	}

	/** Release versions returned per Project, newest first; older versions remain valid filters. */
	static final int MAX_RECENT_RELEASES = 10;

	private final JdbcTemplate jdbc;

	public ProjectListTool(ToolSupport support) {
		this.jdbc = support.jdbc();
	}

	@McpTool(name = "list_projects", title = "List Projects", generateOutputSchema = true,
			annotations = @McpTool.McpAnnotations(title = "List Projects", readOnlyHint = true,
					destructiveHint = false, idempotentHint = true, openWorldHint = false),
			description = """
					Every Project in this Outpost installation, with the Environment Names telemetry has arrived \
					under and the most recently created release versions for each. Call this first: the other Tools \
					identify a Project by its `slug`, an Environment by its exact name and a release by its exact \
					version string, and none of these can be guessed. Takes no parameters.""")
	public ProjectListResult listProjects() {
		SearchQuery projects = ProjectController.buildProjectListQuery();
		SearchQuery environments = ProjectController.buildEnvironmentsQuery(List.of());
		// One row past the cap, so "were releases cut" is answered by the rows in hand.
		SearchQuery releases = ReleaseController.buildRecentReleasesQuery(MAX_RECENT_RELEASES + 1);

		Map<Long, List<String>> environmentsByProject = new LinkedHashMap<>();
		jdbc.query(environments.sql(), rs -> {
			environmentsByProject.computeIfAbsent(rs.getLong("project_id"), id -> new ArrayList<>())
				.add(rs.getString("name"));
		}, environments.params().toArray());

		Map<Long, List<String>> releasesByProject = new LinkedHashMap<>();
		jdbc.query(releases.sql(), rs -> {
			releasesByProject.computeIfAbsent(rs.getLong("project_id"), id -> new ArrayList<>())
				.add(rs.getString("version"));
		}, releases.params().toArray());
		boolean releasesTruncated = truncate(releasesByProject);

		List<ProjectPayload> payloads = jdbc.query(projects.sql(),
				(rs, row) -> new ProjectPayload(rs.getLong("id"), rs.getString("slug"), rs.getString("name"),
						rs.getString("platform"), environmentsByProject.getOrDefault(rs.getLong("id"), List.of()),
						releasesByProject.getOrDefault(rs.getLong("id"), List.of())),
				projects.params().toArray());

		List<String> caveats = new ArrayList<>();
		if (payloads.isEmpty()) {
			caveats.add("This installation has no Projects, so no telemetry can have been received. "
					+ "Every other Tool will return nothing until one is created in the Outpost UI.");
		}
		else if (payloads.stream().anyMatch(project -> project.environments().isEmpty())) {
			// An empty list could be misread as "no environments exist" rather than "none sent yet".
			caveats.add("An empty environments list means no telemetry carrying an Environment Name has been "
					+ "received for that Project yet, not that it has no environments.");
		}
		if (releasesTruncated) {
			caveats.add("recent_releases lists only the " + MAX_RECENT_RELEASES + " most recently created release "
					+ "versions per Project. Older versions still exist and remain valid release filters.");
		}
		return new ProjectListResult(payloads, caveats);
	}

	/** Cuts each Project's list to the cap, reporting whether anything was cut. */
	private static boolean truncate(Map<Long, List<String>> releasesByProject) {
		boolean truncated = false;
		for (Map.Entry<Long, List<String>> entry : releasesByProject.entrySet()) {
			if (entry.getValue().size() > MAX_RECENT_RELEASES) {
				truncated = true;
				entry.setValue(List.copyOf(entry.getValue().subList(0, MAX_RECENT_RELEASES)));
			}
		}
		return truncated;
	}

}
