import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

import { TransactionGroupTrend, TransactionGroupTrendPoint } from '../core/models';
import { bucketCount, bucketIndex } from './bucket-grid';
import { formatDuration } from './ui';

/** Viewbox height. The plot is drawn in these units and stretched to the real width. */
const HEIGHT = 100;

/** Fractions of the axis maximum where gridlines and their labels sit. */
const GRID = [1, 0.5, 0];

/** At most this many labelled boundaries on the time axis, including both edges. */
const MAX_TICKS = 4;

/** Hoisted: constructing an Intl formatter is the expensive part, and this runs on every recompute. */
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
 * p50 and p95 duration trend for one Transaction Group. Both series are drawn
 * together so a reader can tell a broad slowdown from a tail-only one; gaps
 * in the data break the line, and the edge buckets are partial windows.
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

  readonly empty = computed(() => this.plotted().length === 0);

  /** Axis top: the worst p95 in the window, rounded up to a round number. */
  readonly axisMax = computed(() => {
    const peak = Math.max(0, ...this.plotted().map((point) => point.p95));
    return niceCeiling(peak);
  });

  /**
   * Gridline positions and labels. The axis is scaled in raw milliseconds but
   * labelled through `formatDuration`, matching how duration reads elsewhere.
   */
  readonly gridLines = computed<GridLine[]>(() =>
    GRID.map((fraction) => ({
      top: (1 - fraction) * 100,
      y: (1 - fraction) * HEIGHT,
      // Bare '0' at baseline — formatDuration would print "0µs" here.
      text: fraction === 0 ? '0' : formatDuration(fraction * this.axisMax()),
    })),
  );

  /**
   * Up to four labelled tick boundaries (not buckets), so the last label is
   * the plot's right edge rather than the start of the final interval. The
   * outer two hang inward from the edges instead of centering on them.
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

  /** Accessible name for the chart: series and the peak value. */
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
   * One path per series, broken into runs so a gap in the data isn't drawn
   * as a straight line. A run of one point is a short dash — a single-point
   * path renders nothing.
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

/** The next round number at or above `value`: 1, 1.5, 2, 3, 5 or 7.5 times a power of ten. */
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
