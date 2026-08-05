import {
  ChangeDetectionStrategy,
  Component,
  computed,
  debounced,
  effect,
  inject,
  linkedSignal,
  signal,
  untracked,
} from '@angular/core';
import { httpResource } from '@angular/common/http';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Params, Router } from '@angular/router';
import { toSignal } from '@angular/core/rxjs-interop';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmButtonGroup } from '@spartan-ng/helm/button-group';
import { HlmInput } from '@spartan-ng/helm/input';
import { HlmBadge } from '@spartan-ng/helm/badge';
import { HlmSwitch } from '@spartan-ng/helm/switch';
import { HlmTableImports } from '@spartan-ng/helm/table';
import {
  HlmEmpty,
  HlmEmptyHeader,
  HlmEmptyTitle,
  HlmEmptyDescription,
} from '@spartan-ng/helm/empty';
import { HlmSpinner } from '@spartan-ng/helm/spinner';

import { Api } from '../../core/api';
import { API_BASE } from '../../core/api-base';
import { GlobalFilters } from '../../core/filters';
import { LogFilters, LogPage, LogRecord, LogTimeline } from '../../core/models';
import { logParams } from '../../core/query-params';
import { LevelBadge } from '../../shared/level-badge';
import { LogTimelineChart, TimelineWindow } from '../../shared/log-timeline';

const BASE = API_BASE;
const LIVE_BUFFER = 500;

/** Logs page: Kibana-lite filterable stream with SSE live tail. */
@Component({
  selector: 'app-logs',
  imports: [
    DatePipe,
    FormsModule,
    LevelBadge,
    LogTimelineChart,
    HlmButton,
    HlmButtonGroup,
    HlmInput,
    HlmBadge,
    HlmSwitch,
    HlmTableImports,
    HlmEmpty,
    HlmEmptyHeader,
    HlmEmptyTitle,
    HlmEmptyDescription,
    HlmSpinner,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'flex min-h-0 flex-1 flex-col' },
  templateUrl: './logs.html',
})
export class LogsPage {
  private readonly api = inject(Api);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  readonly filters = inject(GlobalFilters);

  readonly allLevels = ['trace', 'debug', 'info', 'warn', 'error', 'fatal'];
  readonly liveBuffer = LIVE_BUFFER;

  private readonly queryParams = toSignal(this.route.queryParams, {
    initialValue: this.route.snapshot.queryParams,
  });

  // Page-local filter state lives in the URL (shareable), like everywhere else.
  readonly selectedLevels = computed<string[]>(() => this.multi(this.queryParams()['level']));
  readonly traceId = computed<string>(() => this.queryParams()['trace_id'] ?? '');

  /**
   * The brush selection, as `window=<fromISO>..<toISO>`. Page-local and deliberately
   * one opaque param: `from` is also what the global range derives, and a URL
   * carrying both would invite someone to move this into `GlobalFilters` — which is
   * the thing ADR 0011 exists to prevent.
   *
   * <p>Validated here rather than at each use, because this one parse feeds three
   * consumers that all take a hand-typed URL badly: `currentFilters` would forward
   * the garbage to the API, the chart would compute NaN bounds and dim every bar
   * with nothing explaining why, and the clear chip's `DatePipe` throws outright.
   * A repeated `?window=` is the same problem — Angular hands back an array, which
   * has no `.split`.
   */
  readonly window = computed<TimelineWindow | null>(() => {
    const [raw] = this.multi(this.queryParams()['window']);
    const [from, to] = (raw ?? '').split('..');
    if (!from || !to) return null;
    const bounds = [Date.parse(from), Date.parse(to)];
    if (bounds.some(Number.isNaN) || bounds[0] >= bounds[1]) return null;
    return { from, to };
  });
  readonly search = signal(this.route.snapshot.queryParams['query'] ?? '');
  readonly debouncedQuery = debounced(this.search, 300);

  readonly live = signal(false);
  // Tracks whether the previous live-effect run was already live, so a filter
  // change while live clears the stale buffer but entering live does not.
  private wasLive = false;
  readonly expanded = signal<ReadonlySet<string>>(new Set());
  readonly copiedId = signal<string | null>(null);

  /**
   * Everything that narrows the stream except time. The three consumers below differ
   * only in which time bounds they add, so they share this rather than each rebuilding
   * the filter set — and the SSE tail, which understands no time bounds at all, uses
   * it as is.
   */
  private readonly baseFilters = computed<LogFilters>(() => ({
    project: this.filters.project(),
    environment: this.filters.environments(),
    level: this.selectedLevels(),
    query: this.debouncedQuery.value() || undefined,
    traceId: this.traceId() || undefined,
  }));

  /**
   * What the chart is drawn from: the global range, and deliberately <em>not</em> the
   * brush. Feeding the selection back in would collapse the chart onto it on every
   * drag, which is the zoom behaviour ADR 0011 rejects.
   */
  private readonly timelineFilters = computed<LogFilters>(() => ({
    ...this.baseFilters(),
    from: this.filters.from(),
  }));

  private readonly filterKey = computed(() =>
    JSON.stringify({
      ...this.timelineFilters(),
      window: this.window(),
    }),
  );

  private readonly cursor = linkedSignal<string, string | undefined>({
    source: this.filterKey,
    computation: () => undefined,
  });

  // Live mode owns `logs` via SSE; the resource goes idle (request fn returns
  // undefined → no fetch, no spinner) until live is switched back off.
  private readonly page = httpResource<LogPage>(() =>
    this.live()
      ? undefined
      : {
          url: `${BASE}/logs`,
          params: logParams({ ...this.currentFilters(), cursor: this.cursor() }),
        },
  );

  // Its own resource, keyed on its own filters: the list refetches on every cursor
  // and on the brush, and neither has any bearing on the chart. Idle while live,
  // where the chart is hidden and the SSE tail has no window to speak of.
  private readonly timeline = httpResource<LogTimeline>(() =>
    this.live()
      ? undefined
      : { url: `${BASE}/logs/timeline`, params: logParams(this.timelineFilters()) },
  );

  readonly timelineData = computed(() => this.timeline.value());

  readonly logs = signal<LogRecord[]>([]);
  readonly loading = this.page.isLoading;
  readonly nextCursor = computed(() => this.page.value()?.next_cursor ?? null);

  constructor() {
    effect(() => {
      const page = this.page.value();
      if (!page) return;
      untracked(() => this.logs.set(this.cursor() ? [...this.logs(), ...page.logs] : page.logs));
    });

    // SSE live tail: while live, prepend new records, filters and all.
    effect((onCleanup) => {
      if (!this.live()) return;
      this.filterKey(); // reconnect when filters change
      // A filter change while already live invalidates the buffered records
      // (they were streamed under the old filter). Entering live keeps the
      // rows the resource already fetched — clear only on the reconnect case.
      if (this.wasLive) this.logs.set([]);
      this.wasLive = true;
      // Base filters, not the list's: a tail has no window. Reading `currentFilters`
      // here would also race the navigation that clears the brush on the way in, and
      // connect a live tail bounded to a closed window in the past.
      const source = new EventSource(this.api.logTailUrl(untracked(() => this.baseFilters())));
      source.onmessage = (message: MessageEvent<string>) => {
        let record: LogRecord;
        try {
          record = JSON.parse(message.data) as LogRecord;
        } catch {
          return;
        }
        this.logs.update((list) => [record, ...list].slice(0, LIVE_BUFFER));
      };
      onCleanup(() => source.close());
    });

    // A brush is a window inside the current scope, so any change of scope makes it
    // meaningless: the selection would sit outside the new range and empty the stream
    // with nothing on screen explaining why. Skips its first run so a shared URL
    // arrives with its selection intact.
    let lastScope: string | null = null;
    effect(() => {
      const scope = JSON.stringify([
        this.filters.project(),
        this.filters.environments(),
        this.filters.range(),
      ]);
      const changed = lastScope !== null && lastScope !== scope;
      lastScope = scope;
      if (changed && untracked(() => this.window())) {
        untracked(() => this.syncUrl({ window: null }));
      }
    });

    let lastSynced = this.search();
    effect(() => {
      const query = this.debouncedQuery.value();
      if (query === lastSynced) return;
      lastSynced = query;
      this.syncUrl({ query: query || null });
    });
  }

  /**
   * The list's filters. A brush selection replaces the range-derived `from` outright
   * and supplies the `to` the range never has — it is the window, not an extra bound
   * on top of one.
   */
  private currentFilters(): LogFilters {
    const selection = this.window();
    return {
      ...this.baseFilters(),
      from: selection ? selection.from : this.filters.from(),
      to: selection ? selection.to : undefined,
    };
  }

  selectWindow(selection: TimelineWindow | null): void {
    this.syncUrl({ window: selection ? `${selection.from}..${selection.to}` : null });
  }

  loadMore(): void {
    const cursor = this.nextCursor();
    if (cursor) this.cursor.set(cursor);
  }

  toggleLive(): void {
    // Leaving live: drop the streamed buffer and reset to page one so the
    // resource's fresh result — not a stale cursor — becomes the source of truth.
    if (this.live()) {
      this.logs.set([]);
      this.cursor.set(undefined);
      this.wasLive = false;
    } else if (this.window()) {
      // Entering live: a closed window in the past and a tail of what is arriving now
      // are contradictory, and the SSE endpoint takes no time bounds at all — leaving
      // the selection in the URL would be state the request deliberately ignores.
      this.syncUrl({ window: null });
    }
    this.live.set(!this.live());
  }

  toggleLevel(level: string): void {
    const levels = this.selectedLevels().includes(level)
      ? this.selectedLevels().filter((l) => l !== level)
      : [...this.selectedLevels(), level];
    this.syncUrl({ level: levels.length ? levels : null });
  }

  filterByTrace(traceId: string): void {
    this.syncUrl({ trace_id: traceId });
  }

  clearTrace(): void {
    this.syncUrl({ trace_id: null });
  }

  toggleExpanded(id: string): void {
    const expanded = new Set(this.expanded());
    if (!expanded.delete(id)) {
      expanded.add(id);
    }
    this.expanded.set(expanded);
  }

  attrEntries(record: LogRecord): [string, string][] {
    return Object.entries(record.attributes).map(([key, value]) => [
      key,
      typeof value === 'object' ? JSON.stringify(value) : String(value),
    ]);
  }

  copyJson(record: LogRecord): void {
    void navigator.clipboard.writeText(JSON.stringify(record, null, 2)).then(() => {
      this.copiedId.set(record.id);
      setTimeout(() => this.copiedId.set(null), 1500);
    });
  }

  private multi(raw: unknown): string[] {
    if (raw == null || raw === '') return [];
    return Array.isArray(raw) ? (raw as string[]) : [raw as string];
  }

  private syncUrl(params: Params): void {
    void this.router.navigate([], { queryParams: params, queryParamsHandling: 'merge' });
  }
}
