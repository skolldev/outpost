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
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { toSignal } from '@angular/core/rxjs-interop';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmButtonGroup } from '@spartan-ng/helm/button-group';
import { HlmInput } from '@spartan-ng/helm/input';
import { HlmNativeSelect, HlmNativeSelectOption } from '@spartan-ng/helm/native-select';
import { HlmBadge } from '@spartan-ng/helm/badge';
import { HlmTableImports } from '@spartan-ng/helm/table';
import {
  HlmEmpty,
  HlmEmptyHeader,
  HlmEmptyTitle,
  HlmEmptyDescription,
} from '@spartan-ng/helm/empty';
import { HlmSpinner } from '@spartan-ng/helm/spinner';

import { API_BASE } from '../core/api-base';
import { GlobalFilters } from '../core/filters';
import { Issue, IssuePage } from '../core/models';
import { issueParams } from '../core/query-params';
import { Sparkline } from '../shared/sparkline';
import { LevelBadge } from '../shared/level-badge';
import { timeAgo } from '../shared/ui';

const BASE = API_BASE;

/** Issues list (§9 page 1): the home page. */
@Component({
  selector: 'app-issues',
  imports: [
    FormsModule,
    RouterLink,
    Sparkline,
    LevelBadge,
    HlmButton,
    HlmButtonGroup,
    HlmInput,
    HlmNativeSelect,
    HlmNativeSelectOption,
    HlmBadge,
    HlmTableImports,
    HlmEmpty,
    HlmEmptyHeader,
    HlmEmptyTitle,
    HlmEmptyDescription,
    HlmSpinner,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'flex min-h-0 flex-1 flex-col' },
  templateUrl: './issues.html',
})
export class IssuesPage {
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  readonly filters = inject(GlobalFilters);

  readonly statusTabs = [
    { value: 'unresolved', label: 'Unresolved' },
    { value: 'resolved', label: 'Resolved' },
    { value: '', label: 'All' },
  ];

  private readonly queryParams = toSignal(this.route.queryParams, {
    initialValue: this.route.snapshot.queryParams,
  });

  // Status/sort live in the URL (shareable, back/forward-aware); search is a
  // local signal for instant input feedback, mirrored to the URL on debounce.
  readonly status = computed<string>(() => this.queryParams()['status'] ?? 'unresolved');
  readonly sort = computed<string>(() => this.queryParams()['sort'] ?? 'last_seen');
  readonly search = signal(this.route.snapshot.queryParams['query'] ?? '');
  readonly debouncedQuery = debounced(this.search, 300);

  readonly timeAgo = timeAgo;

  // Everything the request depends on except the cursor. When any of these
  // change the cursor resets to undefined (linkedSignal) → back to page one.
  private readonly filterKey = computed(() =>
    JSON.stringify({
      project: this.filters.project(),
      environment: this.filters.environments(),
      from: this.filters.from(),
      status: this.status(),
      sort: this.sort(),
      query: this.debouncedQuery.value(),
    }),
  );

  private readonly cursor = linkedSignal<string, string | undefined>({
    source: this.filterKey,
    computation: () => undefined,
  });

  private readonly page = httpResource<IssuePage>(() => ({
    url: `${BASE}/issues`,
    params: issueParams({
      project: this.filters.project(),
      environment: this.filters.environments(),
      from: this.filters.from(),
      status: this.status() || undefined,
      sort: this.sort(),
      query: this.debouncedQuery.value() || undefined,
      cursor: this.cursor(),
    }),
  }));

  // Accumulator: replace on a fresh filter set, append when paging with a cursor.
  readonly issues = signal<Issue[]>([]);
  readonly loading = this.page.isLoading;
  readonly nextCursor = computed(() => this.page.value()?.next_cursor ?? null);

  constructor() {
    effect(() => {
      const page = this.page.value();
      if (!page) return;
      // Read cursor untracked so this only re-runs when a response lands, not
      // when we set the cursor — avoids double-applying a page.
      untracked(() =>
        this.issues.set(this.cursor() ? [...this.issues(), ...page.issues] : page.issues),
      );
    });
    // Keep search shareable in the URL, in step with the debounce.
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

  setStatus(status: string): void {
    this.syncUrl({ status: status === 'unresolved' ? null : status });
  }

  onSortChange(event: Event): void {
    const sort = (event.target as HTMLSelectElement).value;
    this.syncUrl({ sort: sort === 'last_seen' ? null : sort });
  }

  private syncUrl(params: Record<string, unknown>): void {
    void this.router.navigate([], { queryParams: params, queryParamsHandling: 'merge' });
  }
}
