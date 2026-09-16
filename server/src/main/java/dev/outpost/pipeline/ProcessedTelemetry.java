package dev.outpost.pipeline;

/** What every processed signal carries: the project it belongs to, and the environment and release it came from. */
public interface ProcessedTelemetry {

	long projectId();

	String environment();

	/** Null when the SDK did not report one. */
	String release();
}
