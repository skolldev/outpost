package dev.outpost.ingest;

import java.nio.file.Path;

/** The on-disk protocol body of one accepted envelope. */
public record SpoolFile(Path path, boolean gzip) {
}
