// Mirrors the server's bucket ladder (`query/TimeBuckets`): every chart places
// its buckets using the same grid start and width the response reports.

/**
 * How many buckets a window spans, empty ones included — a chart's x extent.
 * `from` must be the grid start the response reports (server-floored), not
 * the window's own start.
 */
export function bucketCount(from: string, to: string, bucketSeconds: number): number {
  const span = Date.parse(to) - Date.parse(from);
  return Math.max(1, Math.ceil(span / (bucketSeconds * 1000)));
}

/**
 * Which bucket an instant falls in, counting from the grid start.
 * `gridFrom` must be the grid start, not the window's own — otherwise every
 * bucket lands one index low.
 */
export function bucketIndex(start: string, gridFrom: number, bucketMs: number): number {
  return Math.floor((Date.parse(start) - gridFrom) / bucketMs);
}
