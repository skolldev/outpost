package dev.outpost.artifacts;

import java.util.List;

/**
 * Published after a bundle is stored; the re-symbolication job (§6.2) listens
 * and re-processes events flagged {@code missing_sourcemap}. Fired inside the
 * assemble transaction — listeners run after commit.
 */
public record BundleAssembledEvent(long bundleId, List<String> projectSlugs, String release) {
}
