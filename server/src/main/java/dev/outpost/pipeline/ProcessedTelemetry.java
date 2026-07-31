package dev.outpost.pipeline;

/**
 * What every processed signal carries whatever its shape — the project it
 * belongs to, and the environment and release it came from. Lets
 * {@link TelemetryOrigins} take a batch of log records, events or transactions
 * as it is, instead of each store lifting the same three fields out of its own
 * rows first.
 */
public interface ProcessedTelemetry {

	long projectId();

	String environment();

	/** Null when the SDK did not report one. */
	String release();
}
