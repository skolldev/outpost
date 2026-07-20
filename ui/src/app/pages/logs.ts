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

import { Api } from '../core/api';
import { API_BASE } from '../core/api-base';
import { GlobalFilters } from '../core/filters';
import { LogFilters, LogPage, LogRecord } from '../core/models';
import { logParams } from '../core/query-params';
import { LevelBadge } from '../shared/level-badge';

const BASE = API_BASE;
const LIVE_BUFFER = 500;

/** Logs page (§9 page 3): Kibana-lite filterable stream with SSE live tail. */
@Component({
  selector: 'app-logs',
  imports: [
    DatePipe,
    FormsModule,
    LevelBadge,
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
  readonly search = signal(this.route.snapshot.queryParams['query'] ?? '');
  readonly debouncedQuery = debounced(this.search, 300);

  readonly live = signal(false);
  // Tracks whether the previous live-effect run was already live, so a filter
  // change while live clears the stale buffer but entering live does not.
  private wasLive = false;
  readonly expanded = signal<ReadonlySet<string>>(new Set());
  readonly copiedId = signal<string | null>(null);

  private readonly filterKey = computed(() =>
    JSON.stringify({
      project: this.filters.project(),
      environment: this.filters.environments(),
      from: this.filters.from(),
      level: this.selectedLevels(),
      traceId: this.traceId(),
      query: this.debouncedQuery.value(),
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

  readonly logs = signal<LogRecord[]>([]);
  readonly loading = this.page.isLoading;
  readonly nextCursor = computed(() => this.page.value()?.next_cursor ?? null);

  constructor() {
    effect(() => {
      const page = this.page.value();
      if (!page) return;
      untracked(() => this.logs.set(this.cursor() ? [...this.logs(), ...page.logs] : page.logs));
    });

    // SSE live tail (§9.3): while live, prepend new records, filters and all.
    effect((onCleanup) => {
      if (!this.live()) return;
      this.filterKey(); // reconnect when filters change
      // A filter change while already live invalidates the buffered records
      // (they were streamed under the old filter). Entering live keeps the
      // rows the resource already fetched — clear only on the reconnect case.
      if (this.wasLive) this.logs.set([]);
      this.wasLive = true;
      const source = new EventSource(this.api.logTailUrl(untracked(() => this.currentFilters())));
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

    let lastSynced = this.search();
    effect(() => {
      const query = this.debouncedQuery.value();
      if (query === lastSynced) return;
      lastSynced = query;
      this.syncUrl({ query: query || null });
    });
  }

  private currentFilters(): LogFilters {
    return {
      project: this.filters.project(),
      environment: this.filters.environments(),
      from: this.filters.from(),
      level: this.selectedLevels(),
      query: this.debouncedQuery.value() || undefined,
      traceId: this.traceId() || undefined,
    };
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
