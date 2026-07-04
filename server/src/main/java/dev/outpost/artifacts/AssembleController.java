package dev.outpost.artifacts;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * sentry-cli artifact-bundle assembly (§4.4). Assembly is synchronous, so the
 * response is immediately final: {@code ok} (done — final for both the CLI's
 * default and {@code --wait} modes), {@code not_found} with the chunks to
 * re-upload, or {@code error}. camelCase keys are part of the wire contract.
 */
@RestController
public class AssembleController {

	public record AssembleRequest(String checksum, List<String> chunks, List<String> projects, String version) {
	}

	private final ArtifactBundleService artifacts;

	public AssembleController(ArtifactBundleService artifacts) {
		this.artifacts = artifacts;
	}

	@PostMapping("/api/0/organizations/{org}/artifactbundle/assemble/")
	public ResponseEntity<?> assemble(@PathVariable String org, @RequestBody AssembleRequest request) {
		return respond(request, request.projects() != null ? request.projects() : List.of());
	}

	/** Legacy per-project path used by some CLI versions; {@code {org}} ignored as everywhere. */
	@PostMapping("/api/0/projects/{org}/{project}/artifactbundle/assemble/")
	public ResponseEntity<?> assembleForProject(@PathVariable String org, @PathVariable String project,
			@RequestBody AssembleRequest request) {
		List<String> projects = new ArrayList<>(request.projects() != null ? request.projects() : List.of());
		if (!projects.contains(project)) {
			projects.add(project);
		}
		return respond(request, projects);
	}

	private ResponseEntity<?> respond(AssembleRequest request, List<String> projects) {
		if (request.checksum() == null || !request.checksum().matches("[0-9a-fA-F]{40}")
				|| request.chunks() == null || request.chunks().isEmpty()) {
			return ResponseEntity.badRequest().body(Map.of("error", "checksum and chunks required"));
		}
		ArtifactBundleService.AssembleResult result = artifacts.assemble(request.checksum(), request.chunks(),
				projects, request.version());
		return switch (result) {
			case ArtifactBundleService.AssembleResult.MissingChunks missing -> ResponseEntity
				.ok(Map.of("state", "not_found", "missingChunks", missing.missing()));
			case ArtifactBundleService.AssembleResult.Error error -> ResponseEntity
				.status(HttpStatus.BAD_REQUEST)
				.body(Map.of("state", "error", "detail", error.detail(), "missingChunks", List.of()));
			case ArtifactBundleService.AssembleResult.Ok ok -> ResponseEntity
				.ok(Map.of("state", "ok", "missingChunks", List.of()));
		};
	}
}
