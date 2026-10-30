package dev.outpost.pipeline;

import java.util.List;

/**
 * One v2 span item after processing. Segments become txn rows; every other span
 * is a span row that may arrive before or after its segment.
 */
public record StreamedSpans(List<ProcessedTransaction> segments, List<ProcessedSpan> spans) {
}
