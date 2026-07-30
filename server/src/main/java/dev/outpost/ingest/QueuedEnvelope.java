package dev.outpost.ingest;

import java.time.Instant;

/** Fixed-size queue entry whose variable-sized envelope body lives on disk. */
public record QueuedEnvelope(long projectId, Instant receivedAt, SpoolFile spoolFile) {
}
