// The client half of the server's bucket ladder (`query/TimeBuckets`): every
// time-series response carries a grid start and a bucket width, and every chart
// drawn from one places its buckets by the same two sums. They live here rather
// than in each chart for the reason the ladder itself is shared — two charts
// disagreeing about where a bucket falls is a difference nothing on either screen
// would explain.

/**
 * How many buckets a window spans, empty ones included — a chart's x extent.
 *
 * `from` is the **grid** start the response reports, not the window's own: the
 * server floors it onto the boundaries `date_bin` bins to, and the count is a
 * ceiling because a window covering 150.9 buckets occupies 151 of them.
 */
export function bucketCount(from: string, to: string, bucketSeconds: number): number {
  const span = Date.parse(to) - Date.parse(from);
  return Math.max(1, Math.ceil(span / (bucketSeconds * 1000)));
}

/**
 * Which bucket an instant falls in, counting from the grid start.
 *
 * Only whole against a `gridFrom` that sits on the grid — hand it the window's own
 * start and every bucket lands one index low, the first floors to `-1` and is
 * dropped, and the chart shifts a bucket left.
 */
export function bucketIndex(start: string, gridFrom: number, bucketMs: number): number {
  return Math.floor((Date.parse(start) - gridFrom) / bucketMs);
}
