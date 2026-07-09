import { ChangeDetectionStrategy, Component, computed, effect, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Params, Router, RouterLink } from '@angular/router';
import { toSignal } from '@angular/core/rxjs-interop';
import { firstValueFrom } from 'rxjs';
import { HlmButton } from '@spartan-ng/helm/button';
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
import { TraceFilters, TraceSummary } from '../core/models';
import { formatDuration, projectColor } from '../shared/ui';

/** Traces page (§9 page 4): searchable list of distributed traces. */
@Component({
  selector: 'app-traces',
  imports: [
    DatePipe,
    FormsModule,
    RouterLink,
    HlmButton,
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
  templateUrl: './traces.html',
})
export class TracesPage {
  private readonly api = inject(Api);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  readonly filters = inject(GlobalFilters);

  readonly formatDuration = formatDuration;
  readonly projectColor = projectColor;

  private readonly queryParams = toSignal(this.route.queryParams, {
    initialValue: this.route.snapshot.queryParams,
  });

  // Page-local filters live in the URL (shareable), like the other pages.
  readonly query = computed<string>(() => this.queryParams()['query'] ?? '');
  readonly hasErrors = computed<boolean>(() => this.queryParams()['has_errors'] === 'true');
  readonly minDuration = computed<number | undefined>(() => this.num(this.queryParams()['min_duration']));

  readonly traces = signal<TraceSummary[]>([]);
  readonly nextCursor = signal<string | null>(null);
  readonly loading = signal(true);
  readonly search = signal('');

  private searchDebounce?: ReturnType<typeof setTimeout>;

  constructor() {
    this.search.set(this.route.snapshot.queryParams['query'] ?? '');
    effect(() => {
      this.queryParams();
      void this.load();
    });
  }

  private currentFilters(): TraceFilters {
    return {
      project: this.filters.project(),
      environment: this.filters.environments(),
      from: this.filters.from(),
      query: this.query() || undefined,
      hasErrors: this.hasErrors() || undefined,
      minDuration: this.minDuration(),
    };
  }

  private async load(cursor?: string): Promise<void> {
    if (!cursor) this.loading.set(true);
    const page = await firstValueFrom(this.api.traces({ ...this.currentFilters(), cursor }));
    this.traces.set(cursor ? [...this.traces(), ...page.traces] : page.traces);
    this.nextCursor.set(page.next_cursor);
    this.loading.set(false);
  }

  loadMore(): void {
    const cursor = this.nextCursor();
    if (cursor) void this.load(cursor);
  }

  onSearch(query: string): void {
    this.search.set(query);
    clearTimeout(this.searchDebounce);
    this.searchDebounce = setTimeout(() => this.syncUrl({ query: query || null }), 300);
  }

  toggleErrorsOnly(): void {
    this.syncUrl({ has_errors: this.hasErrors() ? null : 'true' });
  }

  private num(raw: unknown): number | undefined {
    return raw != null && raw !== '' ? Number(raw) : undefined;
  }

  private syncUrl(params: Params): void {
    void this.router.navigate([], { queryParams: params, queryParamsHandling: 'merge' });
  }
}
