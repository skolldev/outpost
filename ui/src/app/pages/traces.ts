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
import { ActivatedRoute, Params, Router, RouterLink } from '@angular/router';
import { toSignal } from '@angular/core/rxjs-interop';
import { HlmButton } from '@spartan-ng/helm/button';
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

import { GlobalFilters } from '../core/filters';
import { TracePage, TraceSummary } from '../core/models';
import { traceParams } from '../core/query-params';
import { formatDuration, projectColor } from '../shared/ui';

const BASE = '/api/internal';

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
    HlmTableImports,
    HlmEmpty,
    HlmEmptyHeader,
    HlmEmptyTitle,
    HlmEmptyDescription,
    HlmSpinner,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'flex min-h-0 flex-1 flex-col' },
  templateUrl: './traces.html',
})
export class TracesPage {
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  readonly filters = inject(GlobalFilters);

  readonly formatDuration = formatDuration;
  readonly projectColor = projectColor;

  private readonly queryParams = toSignal(this.route.queryParams, {
    initialValue: this.route.snapshot.queryParams,
  });

  readonly hasErrors = computed<boolean>(() => this.queryParams()['has_errors'] === 'true');
  readonly minDuration = computed<number | undefined>(() =>
    this.num(this.queryParams()['min_duration']),
  );

  readonly search = signal(this.route.snapshot.queryParams['query'] ?? '');
  readonly debouncedQuery = debounced(this.search, 300);

  // Request key minus cursor; a change resets the cursor to page one.
  private readonly filterKey = computed(() =>
    JSON.stringify({
      project: this.filters.project(),
      environment: this.filters.environments(),
      from: this.filters.from(),
      hasErrors: this.hasErrors(),
      minDuration: this.minDuration(),
      query: this.debouncedQuery.value(),
    }),
  );

  private readonly cursor = linkedSignal<string, string | undefined>({
    source: this.filterKey,
    computation: () => undefined,
  });

  private readonly page = httpResource<TracePage>(() => ({
    url: `${BASE}/traces`,
    params: traceParams({
      project: this.filters.project(),
      environment: this.filters.environments(),
      from: this.filters.from(),
      hasErrors: this.hasErrors() || undefined,
      minDuration: this.minDuration(),
      query: this.debouncedQuery.value() || undefined,
      cursor: this.cursor(),
    }),
  }));

  readonly traces = signal<TraceSummary[]>([]);
  readonly loading = this.page.isLoading;
  readonly nextCursor = computed(() => this.page.value()?.next_cursor ?? null);

  constructor() {
    effect(() => {
      const page = this.page.value();
      if (!page) return;
      untracked(() =>
        this.traces.set(this.cursor() ? [...this.traces(), ...page.traces] : page.traces),
      );
    });
    let lastSynced = this.search();
    effect(() => {
      const query = this.debouncedQuery.value();
      if (query === lastSynced) return;
      lastSynced = query;
      this.syncUrl({ query: query || null });
    });
  }

  loadMore(): void {
    const cursor = this.nextCursor();
    if (cursor) this.cursor.set(cursor);
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
