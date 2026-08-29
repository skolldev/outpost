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
 * holds, and the Environment Names telemetry has arrived under for each.
 *
 * <p>It is the first call an agent makes, because every other Tool names a
 * Project by slug and names an Environment by string, and neither is guessable —
 * a slug is chosen by whoever created the Project and an Environment Name is
 * whatever an SDK sent. Returning both together is why this is one Tool rather
 * than two: an agent that had to ask for environments per Project would make one
 * call per Project before it could filter anything.
 *
 * <p>Both statements are the controllers' own (ADR-0016), and neither is
 * guarded — see {@link ProjectController#buildProjectListQuery()} for why a
 * ceiling over {@code project} could not be set anywhere it was able to fail.
 */
@Component
public class ProjectListTool {

	public record ProjectListResult(List<ProjectPayload> projects, List<String> caveats) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ProjectPayload(long id, String slug, String name, @Nullable String platform,
			List<String> environments) {
	}

	private final JdbcTemplate jdbc;

	public ProjectListTool(ToolSupport support) {
		this.jdbc = support.jdbc();
	}

	@McpTool(name = "list_projects", title = "List Projects", generateOutputSchema = true,
			annotations = @McpTool.McpAnnotations(title = "List Projects", readOnlyHint = true,
					destructiveHint = false, idempotentHint = true, openWorldHint = false),
			description = """
					Every Project in this Outpost installation, with the Environment Names telemetry has arrived \
					under for each. Call this first: the other Tools identify a Project by its `slug` and an \
					Environment by its exact name, and neither can be guessed. Takes no parameters.""")
	public ProjectListResult listProjects() {
		SearchQuery projects = ProjectController.buildProjectListQuery();
		SearchQuery environments = ProjectController.buildEnvironmentsQuery(List.of());

		Map<Long, List<String>> byProject = new LinkedHashMap<>();
		jdbc.query(environments.sql(), rs -> {
			byProject.computeIfAbsent(rs.getLong("project_id"), id -> new ArrayList<>()).add(rs.getString("name"));
		}, environments.params().toArray());

		List<ProjectPayload> payloads = jdbc.query(projects.sql(),
				(rs, row) -> new ProjectPayload(rs.getLong("id"), rs.getString("slug"), rs.getString("name"),
						rs.getString("platform"), byProject.getOrDefault(rs.getLong("id"), List.of())),
				projects.params().toArray());

		List<String> caveats = new ArrayList<>();
		if (payloads.isEmpty()) {
			caveats.add("This installation has no Projects, so no telemetry can have been received. "
					+ "Every other Tool will return nothing until one is created in the Outpost UI.");
		}
		else if (payloads.stream().anyMatch(project -> project.environments().isEmpty())) {
			// Worth saying because the absence is ambiguous and the two readings lead
			// somewhere different: an agent that reads it as "no environments exist" will
			// stop looking, where the answer is usually that nothing has been sent yet.
			caveats.add("An empty environments list means no telemetry carrying an Environment Name has been "
					+ "received for that Project yet, not that it has no environments.");
		}
		return new ProjectListResult(payloads, caveats);
	}

}
