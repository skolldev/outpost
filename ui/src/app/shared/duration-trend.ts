import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

import { TransactionGroupTrend, TransactionGroupTrendPoint } from '../core/models';
import { bucketCount, bucketIndex } from './bucket-grid';
import { formatDuration } from './ui';

/** Viewbox height. The plot is drawn in these units and stretched to the real width. */
const HEIGHT = 100;

/**
 * Where the gridlines and their labels sit, as a fraction of the axis maximum.
 * Three rather than five: the chart's job is to make a step change obvious, and a
 * denser grid competes with the lines for the eye.
 */
const GRID = [1, 0.5, 0];

/** At most this many labelled boundaries on the time axis, including both edges. */
const MAX_TICKS = 4;

/**
 * Hoisted because these run once per point and once per tick on every recompute,
 * and constructing the formatter is the expensive part of `Intl`.
 */
const DAY_TIME = new Intl.DateTimeFormat(undefined, {
  month: 'short',
  day: 'numeric',
  hour: '2-digit',
  minute: '2-digit',
});

const DAY = new Intl.DateTimeFormat(undefined, { month: 'short', day: 'numeric' });

/** One bucket, placed on the grid the response reports. */
interface Plotted {
  index: number;
  p50: number;
  p95: number;
  label: string;
}

/** A labelled boundary on the time axis, and how it hangs off its own position. */
interface Tick {
  left: number;
  text: string;
  align: 'start' | 'middle' | 'end';
}

interface GridLine {
  top: number;
  y: number;
  text: string;
}

/**
 * The duration trend: p50 and p95 for one Transaction Group, bucketed across the
 * window its statistics were computed over.
 *
 * **Both series are drawn together deliberately.** A single p95 is a ranking tool —
 * "p95 is 800ms" says nothing on its own, where "p95 was 200ms until Tuesday" is a
 * bug report — and the two lines together are what separates *everything got slower*
 * from *only the tail got worse*, which have different causes. One line would answer
 * neither question; three (p99 as well) would be a series computed from a fraction of
 * the samples, jumping around and saying the least.
 *
 * Hand-rolled SVG, per the project's no-charting-library rule, and **not** what
 * `shared/sparkline.ts` is — that is the 84×24 non-interactive issue-row decoration
 * ADR-0011 is explicit about keeping distinct. The viewBox is one unit per bucket with
 * `preserveAspectRatio="none"`, so the geometry is computed once in bucket space and
 * the browser does the horizontal scaling; strokes carry `vector-effect` so they do
 * not stretch with it, and every piece of text lives in HTML around the plot rather
 * than inside a coordinate system that would distort it.
 *
 * Gaps are gaps: an interval with no Transactions is absent from the response, and the
 * line breaks there rather than being drawn straight across a quiet weekend. A bucket
 * with no neighbours is drawn as a short dash, so an isolated point is not an
 * invisible one.
 *
 * **The edge intervals are partial, and the chart says so.** The window is not widened
 * onto the bucket grid — that is what keeps the chart describing the same Transactions
 * as the header (see `TransactionGroupTrend`) — so the first interval starts partway
 * through and the last is still filling, both on fewer samples than the rest. Fewer
 * samples is a noisier percentile, and the right edge is where a reader looks for a
 * regression, so the count is in every bucket's hover text and the caveat is in the
 * footnote rather than left for the reader to infer.
 */
@Component({
  selector: 'app-duration-trend',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './duration-trend.html',
})
export class DurationTrendChart {
  readonly trend = input<TransactionGroupTrend | undefined>();

  /** The window's upper edge, from the response the trend came in — it sets the axis width. */
  readonly to = input<string | undefined>();

  readonly height = HEIGHT;

  readonly p50Color = 'var(--duration-p50)';

  readonly p95Color = 'var(--duration-p95)';

  /** How many buckets the axis spans, empty ones included — the chart's x extent. */
  readonly bucketCount = computed(() => {
    const trend = this.trend();
    const to = this.to();
    if (!trend || !to) return 0;
    return bucketCount(trend.from, to, trend.bucket_seconds);
  });

  readonly plotted = computed<Plotted[]>(() => {
    const trend = this.trend();
    if (!trend) return [];
    const bucketMs = trend.bucket_seconds * 1000;
    const origin = Date.parse(trend.from);
    const count = this.bucketCount();

    return trend.points
      .map((point) => ({
        index: bucketIndex(point.start, origin, bucketMs),
        p50: point.p50_ms,
        p95: point.p95_ms,
        label: this.label(point, Date.parse(point.start), bucketMs),
      }))
      .filter((point) => point.index >= 0 && point.index < count);
  });

  /** Nothing to draw is a state the page has to render, not one it can assume away. */
  readonly empty = computed(() => this.plotted().length === 0);

  /**
   * The top of the axis: the worst p95 in the window, rounded up to a number a person
   * would pick. Scaling to the exact maximum would put the peak on the frame and make
   * every chart's top gridline a different unreadable figure.
   */
  readonly axisMax = computed(() => {
    const peak = Math.max(0, ...this.plotted().map((point) => point.p95));
    return niceCeiling(peak);
  });

  /**
   * The axis measures milliseconds — `duration_ms` straight off the wire, unscaled —
   * but it is *labelled* through `formatDuration`, which is what every other duration
   * in the product is read in. A group whose p95 is four seconds gets a `5.00s` label
   * rather than `5000ms`; the alternative was an axis that disagreed with the header
   * three inches above it about how to write the same number.
   */
  readonly gridLines = computed<GridLine[]>(() =>
    GRID.map((fraction) => ({
      top: (1 - fraction) * 100,
      y: (1 - fraction) * HEIGHT,
      // Bare `0` at the baseline: `formatDuration` would render it as "0µs", which
      // reads as a unit claim where the axis only needs an origin.
      text: fraction === 0 ? '0' : formatDuration(fraction * this.axisMax()),
    })),
  );

  /**
   * Up to four labelled **boundaries** — the instants buckets start and end on, not
   * the buckets themselves, so the last label is the right edge of the plot rather
   * than the beginning of the final interval sitting a bucket short of it.
   *
   * The outer two hang inward from the edges instead of being centred on them, which
   * is what keeps them from overflowing the chart.
   */
  readonly ticks = computed<Tick[]>(() => {
    const trend = this.trend();
    const count = this.bucketCount();
    if (!trend || !count) return [];
    const bucketMs = trend.bucket_seconds * 1000;
    const origin = Date.parse(trend.from);
    const format = trend.bucket_seconds >= 86_400 ? DAY : DAY_TIME;
    const steps = Math.min(MAX_TICKS, count + 1) - 1;

    return Array.from({ length: steps + 1 }, (_, step) => {
      const boundary = Math.round((step / steps) * count);
      return {
        left: (boundary / count) * 100,
        text: format.format(origin + boundary * bucketMs),
        align: step === 0 ? 'start' : step === steps ? 'end' : 'middle',
      } satisfies Tick;
    });
  });

  readonly p50Path = computed(() => this.line((point) => point.p50));

  readonly p95Path = computed(() => this.line((point) => point.p95));

  /**
   * What a screen reader gets, and what the page spec asserts on. It names both series
   * and the peak, because "a chart is here" is not a description of anything.
   */
  readonly summary = computed(() => {
    const trend = this.trend();
    if (!trend || this.empty()) return 'Duration trend';
    const peak = Math.max(...this.plotted().map((point) => point.p95));
    return (
      `p50 and p95 duration per ${formatBucket(trend.bucket_seconds)}, ` +
      `${this.plotted().length} buckets, peak p95 ${formatDuration(peak)}`
    );
  });

  /**
   * One `d` per series, broken into runs of adjacent buckets so a quiet interval is a
   * gap rather than a straight line drawn through it. A run of one is a short dash:
   * a `polyline` of a single point renders nothing at all.
   */
  private line(pick: (point: Plotted) => number): string {
    const max = this.axisMax();
    const y = (value: number) => (HEIGHT - (Math.min(value, max) / max) * HEIGHT).toFixed(2);

    return this.runs()
      .map((run) =>
        run.length === 1
          ? `M${run[0].index + 0.15} ${y(pick(run[0]))}L${run[0].index + 0.85} ${y(pick(run[0]))}`
          : 'M' + run.map((point) => `${point.index + 0.5} ${y(pick(point))}`).join('L'),
      )
      .join(' ');
  }

  private readonly runs = computed<Plotted[][]>(() => {
    const runs: Plotted[][] = [];
    let run: Plotted[] = [];
    for (const point of this.plotted()) {
      if (run.length && point.index !== run[run.length - 1].index + 1) {
        runs.push(run);
        run = [];
      }
      run.push(point);
    }
    if (run.length) runs.push(run);
    return runs;
  });

  private label(point: TransactionGroupTrendPoint, start: number, bucketMs: number): string {
    const span = `${DAY_TIME.format(start)} – ${DAY_TIME.format(start + bucketMs)}`;
    const received = `${point.count.toLocaleString()} Transaction${point.count === 1 ? '' : 's'}`;
    return `${span} — p50 ${formatDuration(point.p50_ms)}, p95 ${formatDuration(point.p95_ms)}, ${received}`;
  }
}

/**
 * The next round number at or above `value`: 1, 1.5, 2, 3, 5 or 7.5 times a power of
 * ten. The half-steps are there because a 620ms peak on a 1000ms axis wastes most of
 * the plot, and a flattened chart is one a step change does not show up on.
 */
function niceCeiling(value: number): number {
  if (!(value > 0)) return 1;
  const magnitude = 10 ** Math.floor(Math.log10(value));
  const normalized = value / magnitude;
  return (
    (([1, 1.5, 2, 3, 5, 7.5, 10] as const).find((step) => normalized <= step) ?? 10) * magnitude
  );
}

/** The bucket width in words, for the chart's accessible name. */
function formatBucket(seconds: number): string {
  const units: [number, string][] = [
    [86_400 * 7, 'week'],
    [86_400, 'day'],
    [3_600, 'hour'],
    [60, 'minute'],
  ];
  for (const [size, unit] of units) {
    if (seconds >= size) {
      const n = Math.round(seconds / size);
      return n === 1 ? unit : `${n} ${unit}s`;
    }
  }
  return `${seconds} seconds`;
}
