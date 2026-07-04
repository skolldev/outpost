import { ChangeDetectionStrategy, Component, effect, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { toSignal } from '@angular/core/rxjs-interop';

import { Api } from '../core/api';
import { GlobalFilters } from '../core/filters';
import { Issue } from '../core/models';
import { Sparkline, levelClass, timeAgo } from '../shared/ui';

/** Issues list (§9 page 1): the home page. */
@Component({
  selector: 'app-issues',
  imports: [FormsModule, RouterLink, Sparkline],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="mb-4 flex items-center gap-3">
      <div class="flex rounded border border-slate-700 text-sm">
        @for (tab of statusTabs; track tab.value) {
          <button
            (click)="setStatus(tab.value)"
            class="px-3 py-1.5"
            [class]="
              status() === tab.value ? 'bg-slate-800 text-white' : 'text-slate-400 hover:text-white'
            "
          >
            {{ tab.label }}
          </button>
        }
      </div>
      <input
        type="search"
        placeholder="Search title or culprit…"
        [ngModel]="search()"
        (ngModelChange)="onSearch($event)"
        class="w-72 rounded border border-slate-700 bg-slate-800 px-3 py-1.5 text-sm placeholder:text-slate-500 focus:border-amber-500 focus:outline-none"
      />
      <select
        [value]="sort()"
        (change)="onSortChange($event)"
        class="ml-auto rounded border border-slate-700 bg-slate-800 px-2 py-1.5 text-sm"
      >
        <option value="last_seen">Sort: last seen</option>
        <option value="count">Sort: event count</option>
      </select>
    </div>

    @if (loading()) {
      <p class="py-12 text-center text-sm text-slate-500">Loading…</p>
    } @else if (issues().length === 0) {
      <div class="rounded-lg border border-slate-800 py-16 text-center">
        <p class="text-slate-400">No issues match the current filters.</p>
        <p class="mt-1 text-sm text-slate-500">
          Waiting for the first event? Check the DSN under Settings → Projects.
        </p>
      </div>
    } @else {
      <div class="overflow-x-auto rounded-lg border border-slate-800">
        <table class="w-full text-sm">
          <thead class="border-b border-slate-800 bg-slate-900 text-left text-xs text-slate-500">
            <tr>
              <th class="px-4 py-2 font-medium">Issue</th>
              <th class="px-3 py-2 text-right font-medium">Events</th>
              <th class="px-3 py-2 text-right font-medium">Users</th>
              <th class="px-3 py-2 font-medium">Envs</th>
              <th class="px-3 py-2 font-medium">Age</th>
              <th class="px-3 py-2 font-medium">Last 14d</th>
            </tr>
          </thead>
          <tbody>
            @for (issue of issues(); track issue.id) {
              <tr
                class="cursor-pointer border-b border-slate-800/60 last:border-0 hover:bg-slate-900"
                [routerLink]="['/issues', issue.id]"
                queryParamsHandling="merge"
              >
                <td class="max-w-xl px-4 py-2.5">
                  <div class="flex items-center gap-2">
                    <span
                      class="rounded px-1.5 py-0.5 text-[10px] font-semibold uppercase"
                      [class]="levelClass(issue.level)"
                      >{{ issue.level }}</span
                    >
                    <span class="truncate font-medium text-slate-100">{{ issue.title }}</span>
                    @if (issue.status === 'resolved') {
                      <span
                        class="rounded border border-emerald-500/40 bg-emerald-500/15 px-1.5 py-0.5 text-[10px] text-emerald-300"
                        >resolved</span
                      >
                    }
                  </div>
                  @if (issue.culprit) {
                    <div class="mt-0.5 truncate text-xs text-slate-500">{{ issue.culprit }}</div>
                  }
                </td>
                <td class="px-3 py-2.5 text-right tabular-nums">{{ issue.event_count }}</td>
                <td class="px-3 py-2.5 text-right tabular-nums">{{ issue.users_affected }}</td>
                <td class="px-3 py-2.5">
                  <div class="flex flex-wrap gap-1">
                    @for (env of issue.environments; track env) {
                      <span
                        class="rounded-full border border-slate-700 px-1.5 py-0.5 text-[10px] text-slate-400"
                        >{{ env }}</span
                      >
                    }
                  </div>
                </td>
                <td class="whitespace-nowrap px-3 py-2.5 text-xs text-slate-400">
                  <div>{{ timeAgo(issue.last_seen) }}</div>
                  <div class="text-slate-600">first {{ timeAgo(issue.first_seen) }}</div>
                </td>
                <td class="px-3 py-2.5">
                  <app-sparkline [data]="issue.sparkline ?? []" />
                </td>
              </tr>
            }
          </tbody>
        </table>
      </div>
      @if (nextCursor()) {
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
export class IssuesPage {
  private readonly api = inject(Api);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  readonly filters = inject(GlobalFilters);

  readonly statusTabs = [
    { value: 'unresolved', label: 'Unresolved' },
    { value: 'resolved', label: 'Resolved' },
    { value: '', label: 'All' },
  ];

  private readonly queryParams = toSignal(this.route.queryParams, { initialValue: {} });

  readonly issues = signal<Issue[]>([]);
  readonly nextCursor = signal<string | null>(null);
  readonly loading = signal(true);
  readonly status = signal('unresolved');
  readonly search = signal('');
  readonly sort = signal('last_seen');

  readonly timeAgo = timeAgo;
  readonly levelClass = levelClass;

  private searchDebounce?: ReturnType<typeof setTimeout>;

  constructor() {
    const params = this.route.snapshot.queryParams;
    this.status.set(params['status'] ?? 'unresolved');
    this.search.set(params['query'] ?? '');
    this.sort.set(params['sort'] ?? 'last_seen');
    effect(() => {
      // Reload whenever any URL-addressable filter changes.
      this.queryParams();
      void this.load();
    });
  }

  private async load(cursor?: string): Promise<void> {
    if (!cursor) this.loading.set(true);
    const page = await firstValueFrom(
      this.api.issues({
        project: this.filters.project(),
        environment: this.filters.environments(),
        from: this.filters.from(),
        status: this.status() || undefined,
        query: this.search() || undefined,
        sort: this.sort(),
        cursor,
      }),
    );
    this.issues.set(cursor ? [...this.issues(), ...page.issues] : page.issues);
    this.nextCursor.set(page.next_cursor);
    this.loading.set(false);
  }

  loadMore(): void {
    const cursor = this.nextCursor();
    if (cursor) void this.load(cursor);
  }

  setStatus(status: string): void {
    this.status.set(status);
    this.syncUrl({ status: status === 'unresolved' ? null : status });
  }

  onSortChange(event: Event): void {
    const sort = (event.target as HTMLSelectElement).value;
    this.sort.set(sort);
    this.syncUrl({ sort: sort === 'last_seen' ? null : sort });
  }

  onSearch(query: string): void {
    this.search.set(query);
    clearTimeout(this.searchDebounce);
    this.searchDebounce = setTimeout(() => this.syncUrl({ query: query || null }), 300);
  }

  private syncUrl(params: Record<string, unknown>): void {
    void this.router.navigate([], { queryParams: params, queryParamsHandling: 'merge' });
  }
}
