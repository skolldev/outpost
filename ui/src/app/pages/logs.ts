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

import { Api } from '../core/api';
import { GlobalFilters } from '../core/filters';
import { LogFilters, LogRecord } from '../core/models';
import { levelClass } from '../shared/ui';

const LIVE_BUFFER = 500;

/** Logs page (§9 page 3): Kibana-lite filterable stream with SSE live tail. */
@Component({
  selector: 'app-logs',
  imports: [DatePipe, FormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="mb-4 flex flex-wrap items-center gap-3">
      <div class="flex items-center gap-1.5">
        @for (level of allLevels; track level) {
          <button
            (click)="toggleLevel(level)"
            class="rounded-full border px-2.5 py-0.5 text-xs"
            [class]="
              selectedLevels().includes(level)
                ? 'border-amber-500 bg-amber-500/15 text-amber-300'
                : 'border-slate-700 text-slate-400 hover:border-slate-500'
            "
          >
            {{ level }}
          </button>
        }
      </div>
      <input
        type="search"
        placeholder="Search log body…"
        [ngModel]="search()"
        (ngModelChange)="onSearch($event)"
        class="w-72 rounded border border-slate-700 bg-slate-800 px-3 py-1.5 text-sm placeholder:text-slate-500 focus:border-amber-500 focus:outline-none"
      />
      @if (traceId()) {
        <button
          (click)="clearTrace()"
          class="flex items-center gap-1 rounded-full border border-sky-500 bg-sky-500/15 px-2.5 py-0.5 font-mono text-xs text-sky-300 hover:bg-sky-500/25"
          title="Remove trace filter"
        >
          trace:{{ traceId() }} <span class="text-sky-400">✕</span>
        </button>
      }
      <button
        (click)="toggleLive()"
        class="ml-auto flex items-center gap-2 rounded border px-3 py-1.5 text-sm"
        [class]="
          live()
            ? 'border-emerald-500 bg-emerald-500/15 text-emerald-300'
            : 'border-slate-700 text-slate-300 hover:border-slate-500'
        "
      >
        <span
          class="inline-block h-2 w-2 rounded-full"
          [class]="live() ? 'animate-pulse bg-emerald-400' : 'bg-slate-600'"
        ></span>
        Live tail
      </button>
    </div>

    @if (loading()) {
      <p class="py-12 text-center text-sm text-slate-500">Loading…</p>
    } @else if (logs().length === 0) {
      <div class="rounded-lg border border-slate-800 py-16 text-center">
        <p class="text-slate-400">No log records match the current filters.</p>
        <p class="mt-1 text-sm text-slate-500">
          Enable logs in the SDK (<code class="rounded bg-slate-900 px-1">enableLogs: true</code> /
          <code class="rounded bg-slate-900 px-1">logs.enabled: true</code>) — snippets under
          Settings → Projects.
        </p>
      </div>
    } @else {
      <div class="overflow-x-auto rounded-lg border border-slate-800">
        <table class="w-full text-sm">
          <thead class="border-b border-slate-800 bg-slate-900 text-left text-xs text-slate-500">
            <tr>
              <th class="w-44 px-3 py-2 font-medium">Time</th>
              <th class="w-16 px-3 py-2 font-medium">Level</th>
              <th class="w-24 px-3 py-2 font-medium">Env</th>
              <th class="px-3 py-2 font-medium">Body</th>
            </tr>
          </thead>
          <tbody class="font-mono text-xs">
            @for (record of logs(); track record.id) {
              <tr
                class="cursor-pointer border-b border-slate-800/60 last:border-0 hover:bg-slate-900"
                (click)="toggleExpanded(record.id)"
              >
                <td class="whitespace-nowrap px-3 py-1.5 text-slate-400">
                  {{ record.timestamp | date: 'MMM d, HH:mm:ss.SSS' }}
                </td>
                <td class="px-3 py-1.5">
                  <span
                    class="rounded px-1.5 py-0.5 text-[10px] font-semibold uppercase"
                    [class]="levelClass(record.level)"
                    >{{ record.level }}</span
                  >
                </td>
                <td class="px-3 py-1.5">
                  <span
                    class="rounded-full border border-slate-700 px-1.5 py-0.5 text-[10px] text-slate-400"
                    >{{ record.environment }}</span
                  >
                </td>
                <td class="max-w-0 px-3 py-1.5">
                  <span class="block truncate text-slate-200">{{ record.body }}</span>
                </td>
              </tr>
              @if (expanded().has(record.id)) {
                <tr class="border-b border-slate-800/60 bg-slate-950/60 last:border-0">
                  <td colspan="4" class="px-6 py-3">
                    <p class="mb-2 whitespace-pre-wrap break-all text-slate-200">
                      {{ record.body }}
                    </p>
                    <div class="grid grid-cols-[auto_1fr] gap-x-4 gap-y-0.5">
                      @if (record.trace_id) {
                        <span class="text-slate-500">trace_id</span>
                        <span>
                          <button
                            (click)="filterByTrace(record.trace_id); $event.stopPropagation()"
                            class="text-sky-300 underline decoration-sky-500/50 hover:text-sky-200"
                            title="Filter logs by this trace"
                          >
                            {{ record.trace_id }}
                          </button>
                        </span>
                      }
                      @if (record.span_id) {
                        <span class="text-slate-500">span_id</span>
                        <span class="text-slate-300">{{ record.span_id }}</span>
                      }
                      @if (record.release) {
                        <span class="text-slate-500">release</span>
                        <span class="text-slate-300">{{ record.release }}</span>
                      }
                      @if (record.severity_number !== null) {
                        <span class="text-slate-500">severity</span>
                        <span class="text-slate-300">{{ record.severity_number }}</span>
                      }
                      @for (attr of attrEntries(record); track attr[0]) {
                        <span class="text-slate-500">{{ attr[0] }}</span>
                        <span class="break-all text-slate-300">{{ attr[1] }}</span>
                      }
                    </div>
                    <button
                      (click)="copyJson(record); $event.stopPropagation()"
                      class="mt-2 rounded border border-slate-700 px-2 py-0.5 text-[10px] text-slate-400 hover:border-slate-500 hover:text-white"
                    >
                      {{ copiedId() === record.id ? 'Copied!' : 'Copy JSON' }}
                    </button>
                  </td>
                </tr>
              }
            }
          </tbody>
        </table>
      </div>
      @if (live()) {
        <p class="mt-3 text-center text-xs text-slate-500">
          Live tail — newest records appear on top (buffer capped at {{ liveBuffer }}).
        </p>
      } @else if (nextCursor()) {
        <div class="mt-4 text-center">
          <button
            (click)="loadMore()"
            class="rounded border border-slate-700 px-4 py-1.5 text-sm text-slate-300 hover:border-slate-500"
          >
            Load more
          </button>
        </div>
      }
    }
  `,
})
export class LogsPage {
  private readonly api = inject(Api);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  readonly filters = inject(GlobalFilters);

  readonly allLevels = ['trace', 'debug', 'info', 'warn', 'error', 'fatal'];
  readonly liveBuffer = LIVE_BUFFER;
  readonly levelClass = levelClass;

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
