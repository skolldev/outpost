package dev.outpost.retention;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Admin-only installation-wide data-retention settings. */
@RestController
@RequestMapping("/api/internal/settings/data-retention")
@PreAuthorize("hasRole('ADMIN')")
public class DataRetentionController {

	public record RetentionResponse(boolean enabled, int retentionDays) {
	}

	public record RetentionRequest(Boolean enabled, Integer retentionDays) {
	}

	private final DataRetentionSettings settings;

	public DataRetentionController(DataRetentionSettings settings) {
		this.settings = settings;
	}

	@GetMapping
	public RetentionResponse get() {
		return response(settings.get());
	}

	@PutMapping
	public ResponseEntity<?> update(@RequestBody RetentionRequest request) {
		if (request == null || request.enabled() == null || request.retentionDays() == null) {
			return ResponseEntity.badRequest()
				.body(Map.of("detail", "enabled and retention_days are required"));
		}
		if (!DataRetentionSettings.isSupported(request.retentionDays())) {
			return ResponseEntity.badRequest()
				.body(Map.of("detail", "retention_days must be one of 30, 60, 90, or 180"));
		}
		return ResponseEntity.ok(response(settings.save(request.enabled(), request.retentionDays())));
	}

	private RetentionResponse response(DataRetentionSettings.Policy policy) {
		return new RetentionResponse(policy.enabled(), policy.retentionDays());
	}
}
