import { ChangeDetectionStrategy, Component, effect, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { toSignal } from '@angular/core/rxjs-interop';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmButtonGroup } from '@spartan-ng/helm/button-group';
import { HlmInput } from '@spartan-ng/helm/input';
import { HlmNativeSelect, HlmNativeSelectOption } from '@spartan-ng/helm/native-select';
import { HlmBadge } from '@spartan-ng/helm/badge';
import {
  HlmEmpty,
  HlmEmptyHeader,
  HlmEmptyTitle,
  HlmEmptyDescription,
} from '@spartan-ng/helm/empty';
import { HlmSpinner } from '@spartan-ng/helm/spinner';

import { Api } from '../core/api';
import { GlobalFilters } from '../core/filters';
import { Issue } from '../core/models';
import { Sparkline } from '../shared/sparkline';
import { LevelBadge } from '../shared/level-badge';
import { timeAgo } from '../shared/ui';

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
    HlmEmpty,
    HlmEmptyHeader,
    HlmEmptyTitle,
    HlmEmptyDescription,
    HlmSpinner,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './issues.html',
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
