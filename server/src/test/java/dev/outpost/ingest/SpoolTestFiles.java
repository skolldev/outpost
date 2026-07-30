package dev.outpost.ingest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class SpoolTestFiles {

	private SpoolTestFiles() {
	}

	static void clear(Path directory) throws IOException {
		Files.createDirectories(directory);
		try (var files = Files.list(directory)) {
			for (Path file : files.toList()) {
				Files.deleteIfExists(file);
			}
		}
	}

	static long count(Path directory) throws IOException {
		try (var files = Files.list(directory)) {
			return files.count();
		}
	}
}
