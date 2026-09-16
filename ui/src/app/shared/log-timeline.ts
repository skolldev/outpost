import { ChangeDetectionStrategy, Component, computed, input, output, signal } from '@angular/core';

import { LogTimeline as TimelineData } from '../core/models';
import { bucketCount, bucketIndex } from './bucket-grid';
import { Level, resolveLevel } from './level-badge';

/** A selected sub-window of the chart, as ISO instants. Half-open: `from <= t < to`. */
export interface TimelineWindow {
  from: string;
  to: string;
}

/** Stacking order, worst at top. Fixed rather than derived per-bucket, so colors stay consistent across bars. */
const STACK_ORDER: Level[] = ['fatal', 'error', 'warn', 'info', 'muted'];

const LEVEL_FILL: Record<Level, string> = {
  fatal: 'var(--level-fatal)',
  error: 'var(--level-error)',
  warn: 'var(--level-warn)',
  info: 'var(--level-info)',
  muted: 'var(--level-muted)',
};

/** Viewbox height. Bars are drawn in these units and stretched to the real width. */
const HEIGHT = 100;

/** Gap between bars, in bar-width units. */
const GAP = 0.18;

/** Hoisted: constructing an Intl formatter is expensive, and `bars` recomputes on every pointer move during a drag. */
const TIME_FORMAT = new Intl.DateTimeFormat(undefined, {
  month: 'short',
  day: 'numeric',
  hour: '2-digit',
  minute: '2-digit',
});

interface Segment {
  level: Level;
  fill: string;
  y: number;
  h: number;
}

interface Bar {
  index: number;
  start: number;
  total: number;
  segments: Segment[];
  label: string;
  selected: boolean;
}

/**
 * Log timeline: bucketed counts above the log stream, stacked by level.
 * Dragging or clicking selects a sub-window that narrows the stream below
 * without zooming the chart itself (ADR 0011).
 */
@Component({
  selector: 'app-log-timeline',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './log-timeline.html',
})
export class LogTimelineChart {
  readonly data = input<TimelineData | undefined>();
  readonly window = input<TimelineWindow | null>(null);
  readonly windowChange = output<TimelineWindow | null>();

  readonly height = HEIGHT;
  readonly barWidth = 1 - GAP;

  /** Drag anchor, in bucket indexes; null when not dragging. */
  private readonly dragFrom = signal<number | null>(null);
  private readonly dragTo = signal<number | null>(null);

  readonly bucketCount = computed(() => {
    const data = this.data();
    if (!data) return 0;
    return bucketCount(data.from, data.to, data.bucket_seconds);
  });

  /** The selection currently being dragged, if any — it previews before it is committed. */
  private readonly dragRange = computed<[number, number] | null>(() => {
    const from = this.dragFrom();
    const to = this.dragTo();
    if (from === null || to === null) return null;
    return [Math.min(from, to), Math.max(from, to)];
  });

  /** The committed selection as bucket indexes, for rendering the dim/highlight. */
  private readonly selectedRange = computed<[number, number] | null>(() => {
    const dragged = this.dragRange();
    if (dragged) return dragged;
    const data = this.data();
    const selection = this.window();
    if (!data || !selection) return null;
    const bucketMs = data.bucket_seconds * 1000;
    const origin = Date.parse(data.from);
    const first = Math.floor((Date.parse(selection.from) - origin) / bucketMs);
    // `to` is exclusive, so the last selected bucket is the one before it.
    const last = Math.ceil((Date.parse(selection.to) - origin) / bucketMs) - 1;
    return [first, Math.max(first, last)];
  });

  readonly bars = computed<Bar[]>(() => {
    const data = this.data();
    if (!data) return [];

    const bucketMs = data.bucket_seconds * 1000;
    const origin = Date.parse(data.from);
    const counts = new Map<number, Record<string, number>>();
    for (const bucket of data.buckets) {
      counts.set(bucketIndex(bucket.start, origin, bucketMs), bucket.counts);
    }

    const totals = [...counts.values()].map((c) => Object.values(c).reduce((a, b) => a + b, 0));
    const max = Math.max(1, ...totals);
    const selection = this.selectedRange();

    return Array.from({ length: this.bucketCount() }, (_, index) => {
      const start = origin + index * bucketMs;
      const byLevel = this.byCanonicalLevel(counts.get(index));
      const total = STACK_ORDER.reduce((sum, level) => sum + (byLevel[level] ?? 0), 0);

      // Height each level first, then set the top from their sum — rounding
      // the total separately can put a 1-unit segment below the viewBox and clip it.
      const heights = STACK_ORDER.map((level) => {
        const value = byLevel[level] ?? 0;
        return value ? Math.max(1, Math.round((value / max) * HEIGHT)) : 0;
      });
      let y = Math.max(0, HEIGHT - heights.reduce((a, b) => a + b, 0));
      const segments: Segment[] = [];
      STACK_ORDER.forEach((level, i) => {
        if (!heights[i]) return;
        segments.push({ level, fill: LEVEL_FILL[level], y, h: heights[i] });
        y += heights[i];
      });

      return {
        index,
        start,
        total,
        segments,
        label: this.label(start, start + bucketMs, byLevel, total),
        selected: !selection || (index >= selection[0] && index <= selection[1]),
      };
    });
  });

  onPointerDown(event: PointerEvent): void {
    const index = this.indexAt(event);
    if (index === null) return;
    (event.target as Element).setPointerCapture?.(event.pointerId);
    this.dragFrom.set(index);
    this.dragTo.set(index);
  }

  onPointerMove(event: PointerEvent): void {
    if (this.dragFrom() === null) return;
    const index = this.indexAt(event);
    if (index !== null) this.dragTo.set(index);
  }

  /** A press and a drag commit the same way — a click is just a one-bucket drag. */
  onPointerUp(): void {
    const range = this.dragRange();
    this.dragFrom.set(null);
    this.dragTo.set(null);
    const data = this.data();
    if (!range || !data) return;

    const bucketMs = data.bucket_seconds * 1000;
    const origin = Date.parse(data.from);
    this.windowChange.emit({
      from: new Date(origin + range[0] * bucketMs).toISOString(),
      // Snapped to the end of the last selected bucket, exclusive.
      to: new Date(origin + (range[1] + 1) * bucketMs).toISOString(),
    });
  }

  /** Which bucket the pointer is over, from its position across the plotted width. */
  private indexAt(event: PointerEvent): number | null {
    const svg = event.currentTarget as SVGElement | null;
    const count = this.bucketCount();
    if (!svg || !count) return null;
    const box = svg.getBoundingClientRect();
    if (!box.width) return null;
    const ratio = (event.clientX - box.left) / box.width;
    return Math.min(count - 1, Math.max(0, Math.floor(ratio * count)));
  }

  private byCanonicalLevel(
    counts: Record<string, number> | undefined,
  ): Partial<Record<Level, number>> {
    const byLevel: Partial<Record<Level, number>> = {};
    for (const [level, value] of Object.entries(counts ?? {})) {
      const canonical = resolveLevel(level);
      byLevel[canonical] = (byLevel[canonical] ?? 0) + value;
    }
    return byLevel;
  }

  private label(
    start: number,
    end: number,
    byLevel: Partial<Record<Level, number>>,
    total: number,
  ): string {
    const span = `${TIME_FORMAT.format(start)} – ${TIME_FORMAT.format(end)}`;
    if (!total) return `${span} — no records`;
    const breakdown = STACK_ORDER.filter((level) => byLevel[level])
      .map((level) => `${byLevel[level]} ${level}`)
      .join(', ');
    return `${span} — ${breakdown}`;
  }
}
