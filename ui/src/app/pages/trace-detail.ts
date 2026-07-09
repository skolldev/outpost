import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  signal,
} from '@angular/core';
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmBadge } from '@spartan-ng/helm/badge';
import { HlmSpinner } from '@spartan-ng/helm/spinner';
import { HlmPopoverImports } from '@spartan-ng/helm/popover';

import { Api } from '../core/api';
import { TraceDetail, TraceError, TraceSpan, TraceTransaction } from '../core/models';
import { formatDuration, projectColor } from '../shared/ui';

/** A node in the waterfall — a transaction (service root) or one of its spans. */
export interface WaterfallRow {
  key: string;
  kind: 'transaction' | 'span';
  spanId: string;
  projectId: number;
  title: string;
  op: string | null;
  status: string | null;
  depth: number;
  startMs: number; // offset from trace start
  durationMs: number;
  leftPct: number;
  widthPct: number;
  endPct: number; // leftPct + widthPct, clamped to 100
  color: string;
  errors: TraceError[];
  transaction?: TraceTransaction;
  span?: TraceSpan;
}

/** A log positioned on the trace timeline for the collapsible log lane. */
interface LogMarker {
  id: string;
  leftPct: number;
  offsetMs: number; // offset from trace start
  level: string;
  body: string;
  timestamp: string;
}

/**
 * Trace waterfall (§9.4): nested spans across both services color-coded by
 * project, ordered by start_ts and nested by parent_span_id, with error events
 * pinned on their spans, a collapsible log lane, and a span detail side panel.
 * This is the G3 payoff — browser pageload → fetch span → backend txn → JDBC
 * spans in one view.
 */
@Component({
  selector: 'app-trace-detail',
  imports: [RouterLink, HlmButton, HlmBadge, HlmSpinner, HlmPopoverImports],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './trace-detail.html',
})
export class TraceDetailPage {
  private readonly api = inject(Api);

  readonly traceId = input.required<string>();

  readonly formatDuration = formatDuration;
  readonly projectColor = projectColor;

  readonly trace = signal<TraceDetail | null>(null);
  readonly loading = signal(true);
  readonly notFound = signal(false);
  readonly selected = signal<WaterfallRow | null>(null);
  readonly showLogs = signal(true);

  constructor() {
    effect(() => {
      const id = this.traceId();
      void this.load(id);
    });
  }

  private async load(id: string): Promise<void> {
    this.loading.set(true);
    this.notFound.set(false);
    this.selected.set(null);
    try {
      this.trace.set(await firstValueFrom(this.api.trace(id)));
    } catch {
      this.trace.set(null);
      this.notFound.set(true);
    } finally {
      this.loading.set(false);
    }
  }

  /**
   * Trace window [start, end] in epoch ms. Spanned across transactions and spans
   * — but also errors and logs, because a trace can arrive with only those (an
   * error + its logs, no transaction). Without them the window would collapse to
   * the 1970 fallback and every log marker would pile up off the right edge.
   */
  private readonly window = computed(() => {
    const t = this.trace();
    if (!t) return { start: 0, end: 1 };
    const starts: number[] = [];
    const ends: number[] = [];
    for (const txn of t.transactions) {
      starts.push(ms(txn.start_ts));
      ends.push(ms(txn.end_ts));
    }
    for (const span of t.spans) {
      starts.push(ms(span.start_ts));
      ends.push(ms(span.end_ts));
    }
    for (const error of t.errors) {
      starts.push(ms(error.timestamp));
      ends.push(ms(error.timestamp));
    }
    for (const logRecord of t.logs) {
      starts.push(ms(logRecord.timestamp));
      ends.push(ms(logRecord.timestamp));
    }
    const start = starts.length ? Math.min(...starts) : 0;
    const end = ends.length ? Math.max(...ends) : start + 1;
    return { start, end: Math.max(end, start + 1) };
  });

  readonly totalMs = computed(() => {
    const w = this.window();
    return w.end - w.start;
  });

  /** Flattened, depth-ordered waterfall rows with bar geometry and pinned errors. */
  readonly rows = computed<WaterfallRow[]>(() => {
    const t = this.trace();
    if (!t) return [];
    const { start } = this.window();
    const total = this.totalMs();

    // Every node keyed by its span_id: a transaction is the root span of its
    // service, its spans are children, and a downstream transaction hangs off an
    // upstream span via parent_span_id.
    interface Node {
      kind: 'transaction' | 'span';
      spanId: string;
      parentSpanId: string | null;
      startMs: number;
      endMs: number;
      projectId: number;
      title: string;
      op: string | null;
      status: string | null;
      transaction?: TraceTransaction;
      span?: TraceSpan;
      children: Node[];
    }

    const byId = new Map<string, Node>();
    const nodes: Node[] = [];
    for (const txn of t.transactions) {
      const node: Node = {
        kind: 'transaction',
        spanId: txn.span_id,
        parentSpanId: txn.parent_span_id,
        startMs: ms(txn.start_ts),
        endMs: ms(txn.end_ts),
        projectId: txn.project_id,
        title: txn.name,
        op: txn.op,
        status: txn.status,
        transaction: txn,
        children: [],
      };
      byId.set(txn.span_id, node);
      nodes.push(node);
    }
    for (const span of t.spans) {
      // A span_id collision with a transaction's root span shouldn't happen, but
      // if it does, keep the transaction as the canonical node.
      if (byId.has(span.span_id)) continue;
      const node: Node = {
        kind: 'span',
        spanId: span.span_id,
        parentSpanId: span.parent_span_id,
        startMs: ms(span.start_ts),
        endMs: ms(span.end_ts),
        projectId: span.project_id,
        title: span.description ?? span.op ?? span.span_id,
        op: span.op,
        status: span.status,
        span,
        children: [],
      };
      byId.set(span.span_id, node);
      nodes.push(node);
    }

    // Link children to parents; anything without a known parent is a root.
    const roots: Node[] = [];
    for (const node of nodes) {
      const parent = node.parentSpanId ? byId.get(node.parentSpanId) : undefined;
      if (parent) parent.children.push(node);
      else roots.push(node);
    }

    // Pin each error to the node matching its span_id (falls back to nearest root).
    const errorsBySpan = new Map<string, TraceError[]>();
    for (const error of t.errors) {
      const key = error.span_id && byId.has(error.span_id) ? error.span_id : roots[0]?.spanId;
      if (!key) continue;
      const list = errorsBySpan.get(key) ?? [];
      list.push(error);
      errorsBySpan.set(key, list);
    }

    const rows: WaterfallRow[] = [];
    const walk = (node: Node, depth: number): void => {
      const duration = Math.max(0, node.endMs - node.startMs);
      const offset = node.startMs - start;
      const leftPct = total > 0 ? (offset / total) * 100 : 0;
      const widthPct = total > 0 ? Math.max(0.5, (duration / total) * 100) : 100;
      rows.push({
        key: node.spanId,
        kind: node.kind,
        spanId: node.spanId,
        projectId: node.projectId,
        title: node.title,
        op: node.op,
        status: node.status,
        depth,
        startMs: offset,
        durationMs: duration,
        leftPct,
        widthPct,
        endPct: Math.min(100, leftPct + widthPct),
        color: projectColor(node.projectId),
        errors: errorsBySpan.get(node.spanId) ?? [],
        transaction: node.transaction,
        span: node.span,
      });
      node.children
        .sort((a, b) => a.startMs - b.startMs)
        .forEach((child) => walk(child, depth + 1));
    };
    roots.sort((a, b) => a.startMs - b.startMs).forEach((root) => walk(root, 0));
    return rows;
  });

  /** Distinct projects in this trace, for the color legend. */
  readonly projects = computed(() => {
    const ids = new Set<number>();
    for (const row of this.rows()) ids.add(row.projectId);
    return [...ids].map((id) => ({ id, color: projectColor(id) }));
  });

  readonly logMarkers = computed<LogMarker[]>(() => {
    const t = this.trace();
    if (!t) return [];
    const { start } = this.window();
    const total = this.totalMs();
    return t.logs.map((logRecord) => ({
      id: logRecord.id,
      leftPct:
        total > 0
          ? Math.min(100, Math.max(0, ((ms(logRecord.timestamp) - start) / total) * 100))
          : 0,
      offsetMs: ms(logRecord.timestamp) - start,
      level: logRecord.level,
      body: logRecord.body,
      timestamp: logRecord.timestamp,
    }));
  });

  readonly errorCount = computed(() => this.trace()?.errors.length ?? 0);
  readonly logCount = computed(() => this.trace()?.logs.length ?? 0);

  select(row: WaterfallRow): void {
    this.selected.set(this.selected()?.key === row.key ? null : row);
  }

  toggleLogs(): void {
    this.showLogs.set(!this.showLogs());
  }

  levelColor(level: string): string {
    switch (level) {
      case 'fatal':
      case 'error':
        return 'var(--level-error)';
      case 'warn':
      case 'warning':
        return 'var(--level-warn)';
      case 'info':
        return 'var(--level-info)';
      default:
        return 'var(--level-muted)';
    }
  }

  dataEntries(row: WaterfallRow): [string, string][] {
    const raw = (row.span?.data ?? row.transaction?.data ?? {}) as Record<string, unknown>;
    // Spans carry their useful facts in a nested OTel attribute bag (`data`);
    // flatten it up so the panel shows `http.method`, `url.full`, … directly
    // instead of one giant JSON blob. Top-level keys (e.g. `origin`) come along too.
    const flat: Record<string, unknown> = { ...raw };
    if (raw['data'] && typeof raw['data'] === 'object' && !Array.isArray(raw['data'])) {
      delete flat['data'];
      Object.assign(flat, raw['data'] as Record<string, unknown>);
    }
    return (
      Object.entries(flat)
        .filter(([key]) => !HIDDEN_DATA_KEYS.has(key) && key !== 'spans')
        // Scalars only — nested objects/arrays would just be walls of JSON here.
        .filter(([, value]) => value !== null && typeof value !== 'object')
        .map(([key, value]) => [key, String(value)])
    );
  }
}

// Keys not worth a row in the side panel: redundant with columns already shown,
// or absolute-epoch resource-timing fields that read as meaningless numbers.
const HIDDEN_DATA_KEYS = new Set<string>([
  'sentry.op',
  'sentry.origin',
  'network.protocol.name',
  'http.request.fetch_start',
  'http.request.worker_start',
  'http.request.redirect_start',
  'http.request.redirect_end',
  'http.request.connect_start',
  'http.request.connection_end',
  'http.request.request_start',
  'http.request.response_start',
  'http.request.response_end',
  'http.request.domain_lookup_start',
  'http.request.domain_lookup_end',
  'http.request.secure_connection_start',
]);

function ms(iso: string): number {
  return new Date(iso).getTime();
}
