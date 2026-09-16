import {
  ChangeDetectionStrategy,
  Component,
  computed,
  debounced,
  effect,
  ElementRef,
  inject,
  linkedSignal,
  signal,
  untracked,
  viewChild,
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
import {
  FieldFilter,
  formatAttr,
  isFilterable,
  isFilterableKey,
  isRecordField,
  keySuggestions,
  parseAttr,
  valueSuggestions,
} from './attribute-filters';

const BASE = API_BASE;
const LIVE_BUFFER = 500;

/** Filter sets are values, not identities — see `baseFilters`. */
const sameFilters = (a: LogFilters, b: LogFilters): boolean =>
  JSON.stringify(a) === JSON.stringify(b);

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
  readonly release = computed<string>(() => this.queryParams()['release'] ?? '');
  readonly attributeFilters = computed<FieldFilter[]>(() =>
    parseAttr(this.multi(this.queryParams()['attr'])),
  );

  /** Every active field filter as one chip each — columns and Attributes alike. */
  readonly filterChips = computed<FieldFilter[]>(() => [
    ...(this.traceId() ? [{ key: 'trace_id', value: this.traceId() }] : []),
    ...(this.release() ? [{ key: 'release', value: this.release() }] : []),
    ...this.attributeFilters(),
  ]);

  /**
   * The brush selection as `window=<fromISO>..<toISO>`, kept as one opaque URL param
   * rather than split into `GlobalFilters` (ADR 0011). Validated here since
   * `currentFilters`, the chart and the clear chip's `DatePipe` all handle malformed
   * input badly.
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
  // True once live streaming has started, so a filter change while live clears the buffer but entering live does not.
  private wasLive = false;
  readonly expanded = signal<ReadonlySet<string>>(new Set());
  readonly copiedId = signal<string | null>(null);

  // Add-filter builder; suggestions come from already-loaded records, so they're free but incomplete.
  readonly builderOpen = signal(false);
  readonly draftKey = signal('');
  readonly draftValue = signal('');
  readonly keySuggestions = computed(() => keySuggestions(this.logs()));
  readonly valueSuggestions = computed(() => valueSuggestions(this.logs(), this.draftKey().trim()));
  readonly canSubmitDraft = computed(() => isFilterableKey(this.draftKey().trim()));
  private readonly keyInput = viewChild<ElementRef<HTMLInputElement>>('keyInput');

  /**
   * Filters shared by the list, timeline and SSE tail requests, minus time bounds.
   * Compared by value, not identity — `project`/`environments`/`selectedLevels` return
   * fresh arrays on any param change, so identity comparison would refetch the chart
   * on every brush drag (ADR 0011).
   */
  private readonly baseFilters = computed<LogFilters>(
    () => ({
      project: this.filters.project(),
      environment: this.filters.environments(),
      level: this.selectedLevels(),
      query: this.debouncedQuery.value() || undefined,
      traceId: this.traceId() || undefined,
      release: this.release() || undefined,
      attr: formatAttr(this.attributeFilters()),
    }),
    { equal: sameFilters },
  );

  /**
   * The chart's filters: the global range, deliberately not the brush — feeding the
   * selection back in would collapse the chart onto it on every drag (ADR 0011).
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

  // Live mode owns `logs` via SSE; returning undefined here keeps the resource idle (no fetch, no spinner) until live ends.
  private readonly page = httpResource<LogPage>(() =>
    this.live()
      ? undefined
      : {
          url: `${BASE}/logs`,
          params: logParams({ ...this.currentFilters(), cursor: this.cursor() }),
        },
  );

  // Separate resource from the list's — refetches on cursor/brush, neither of which the chart needs; idle while live.
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
    effect(() => this.keyInput()?.nativeElement.focus());

    effect(() => {
      const page = this.page.value();
      if (!page) return;
      untracked(() => this.logs.set(this.cursor() ? [...this.logs(), ...page.logs] : page.logs));
    });

    // SSE live tail: prepends new records while live.
    effect((onCleanup) => {
      if (!this.live()) return;
      this.filterKey(); // reconnect when filters change
      // Reconnect (filter change while live) clears stale buffered records; entering live keeps what the resource already fetched.
      if (this.wasLive) this.logs.set([]);
      this.wasLive = true;
      // Base filters only: a tail has no window, and `currentFilters` would race the brush-clearing navigation and bind to a stale window.
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

    // Scope change makes the brush meaningless (selection outside new range empties the stream); skip the first run so a shared URL's selection survives.
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
   * The list's filters. A brush selection replaces the range-derived `from` and
   * supplies `to` outright — it's the window, not an added bound.
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
    // Leaving live: drop the buffer and reset to page one so the resource's fresh result, not a stale cursor, is the source of truth.
    if (this.live()) {
      this.logs.set([]);
      this.cursor.set(undefined);
      this.wasLive = false;
    } else if (this.window()) {
      // Entering live: a closed past window contradicts a live tail, and the SSE endpoint ignores time bounds anyway, so clear the selection.
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

  openBuilder(): void {
    this.builderOpen.set(true);
  }

  closeBuilder(): void {
    this.builderOpen.set(false);
    this.draftKey.set('');
    this.draftValue.set('');
  }

  /** Typed text is trimmed here, not in `addFilter`: a clicked value is already exactly what a record holds. */
  submitDraft(): void {
    if (!this.canSubmitDraft()) return;
    this.addFilter(this.draftKey().trim(), this.draftValue().trim());
  }

  /**
   * Narrows the stream to records whose field equals `value` exactly (ADR-0018 —
   * textual equality). One chip per key: a second value replaces the first rather
   * than ANDing.
   */
  addFilter(key: string, value: string): void {
    if (!isFilterableKey(key)) return;
    this.closeBuilder();
    if (isRecordField(key)) {
      this.syncUrl({ [key]: value || null });
      return;
    }
    const others = this.attributeFilters().filter((filter) => filter.key !== key);
    this.syncUrl({ attr: this.attrParam([...others, { key, value }]) });
  }

  removeFilter(key: string): void {
    if (isRecordField(key)) {
      this.syncUrl({ [key]: null });
      return;
    }
    this.syncUrl({
      attr: this.attrParam(this.attributeFilters().filter((filter) => filter.key !== key)),
    });
  }

  toggleExpanded(id: string): void {
    const expanded = new Set(this.expanded());
    if (!expanded.delete(id)) {
      expanded.add(id);
    }
    this.expanded.set(expanded);
  }

  attrEntries(record: LogRecord): { key: string; text: string; filterable: boolean }[] {
    return Object.entries(record.attributes).map(([key, value]) => ({
      key,
      text: typeof value === 'object' ? JSON.stringify(value) : String(value),
      filterable: isFilterable(value) && isFilterableKey(key),
    }));
  }

  copyJson(record: LogRecord): void {
    void navigator.clipboard.writeText(JSON.stringify(record, null, 2)).then(() => {
      this.copiedId.set(record.id);
      setTimeout(() => this.copiedId.set(null), 1500);
    });
  }

  private attrParam(filters: FieldFilter[]): string[] | null {
    return filters.length ? formatAttr(filters) : null;
  }

  private multi(raw: unknown): string[] {
    if (raw == null || raw === '') return [];
    return Array.isArray(raw) ? (raw as string[]) : [raw as string];
  }

  private syncUrl(params: Params): void {
    void this.router.navigate([], { queryParams: params, queryParamsHandling: 'merge' });
  }
}
