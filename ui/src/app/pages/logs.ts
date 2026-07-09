import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  signal,
} from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Params, Router } from '@angular/router';
import { toSignal } from '@angular/core/rxjs-interop';
import { firstValueFrom } from 'rxjs';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmButtonGroup } from '@spartan-ng/helm/button-group';
import { HlmInput } from '@spartan-ng/helm/input';
import { HlmBadge } from '@spartan-ng/helm/badge';
import { HlmSwitch } from '@spartan-ng/helm/switch';
import {
  HlmEmpty,
  HlmEmptyHeader,
  HlmEmptyTitle,
  HlmEmptyDescription,
} from '@spartan-ng/helm/empty';
import { HlmSpinner } from '@spartan-ng/helm/spinner';

import { Api } from '../core/api';
import { GlobalFilters } from '../core/filters';
import { LogFilters, LogRecord } from '../core/models';
import { LevelBadge } from '../shared/level-badge';

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
    HlmEmpty,
    HlmEmptyHeader,
    HlmEmptyTitle,
    HlmEmptyDescription,
    HlmSpinner,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
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
  readonly query = computed<string>(() => this.queryParams()['query'] ?? '');
  readonly traceId = computed<string>(() => this.queryParams()['trace_id'] ?? '');

  readonly logs = signal<LogRecord[]>([]);
  readonly nextCursor = signal<string | null>(null);
  readonly loading = signal(true);
  readonly live = signal(false);
  readonly search = signal('');
  readonly expanded = signal<ReadonlySet<string>>(new Set());
  readonly copiedId = signal<string | null>(null);

  private searchDebounce?: ReturnType<typeof setTimeout>;

  constructor() {
    this.search.set(this.route.snapshot.queryParams['query'] ?? '');
    effect(() => {
      // Reload whenever any URL-addressable filter changes (global or local).
      this.queryParams();
      void this.load();
    });
    effect((onCleanup) => {
      if (!this.live()) return;
      this.queryParams(); // reconnect with the new filters
      const source = new EventSource(this.api.logTailUrl(this.currentFilters()));
      source.onmessage = (message: MessageEvent<string>) => {
        const record = JSON.parse(message.data) as LogRecord;
        this.logs.update((list) => [record, ...list].slice(0, LIVE_BUFFER));
      };
      onCleanup(() => source.close());
    });
  }

  private currentFilters(): LogFilters {
    return {
      project: this.filters.project(),
      environment: this.filters.environments(),
      from: this.filters.from(),
      level: this.selectedLevels(),
      query: this.query() || undefined,
      traceId: this.traceId() || undefined,
    };
  }

  private async load(cursor?: string): Promise<void> {
    if (!cursor) this.loading.set(true);
    const page = await firstValueFrom(this.api.logs({ ...this.currentFilters(), cursor }));
    this.logs.set(cursor ? [...this.logs(), ...page.logs] : page.logs);
    this.nextCursor.set(page.next_cursor);
    this.loading.set(false);
  }

  loadMore(): void {
    const cursor = this.nextCursor();
    if (cursor) void this.load(cursor);
  }

  toggleLive(): void {
    this.live.set(!this.live());
  }

  toggleLevel(level: string): void {
    const levels = this.selectedLevels().includes(level)
      ? this.selectedLevels().filter((l) => l !== level)
      : [...this.selectedLevels(), level];
    this.syncUrl({ level: levels.length ? levels : null });
  }

  onSearch(query: string): void {
    this.search.set(query);
    clearTimeout(this.searchDebounce);
    this.searchDebounce = setTimeout(() => this.syncUrl({ query: query || null }), 300);
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
