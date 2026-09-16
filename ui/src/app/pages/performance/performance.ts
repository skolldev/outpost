import {
  ChangeDetectionStrategy,
  Component,
  computed,
  debounced,
  effect,
  inject,
  signal,
} from '@angular/core';
import { httpResource } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { toSignal } from '@angular/core/rxjs-interop';
import { HlmBadge } from '@spartan-ng/helm/badge';
import { HlmAlert, HlmAlertDescription, HlmAlertTitle } from '@spartan-ng/helm/alert';
import { HlmInput } from '@spartan-ng/helm/input';
import { HlmNativeSelect, HlmNativeSelectOption } from '@spartan-ng/helm/native-select';
import { HlmTableImports } from '@spartan-ng/helm/table';
import {
  HlmEmpty,
  HlmEmptyHeader,
  HlmEmptyTitle,
  HlmEmptyDescription,
} from '@spartan-ng/helm/empty';
import { HlmSpinner } from '@spartan-ng/helm/spinner';

import { API_BASE } from '../../core/api-base';
import { GlobalFilters } from '../../core/filters';
import {
  Release,
  TransactionGroup,
  TransactionGroupPage,
  TransactionGroupSort,
} from '../../core/models';
import { ProjectsStore } from '../../core/projects';
import { transactionGroupParams } from '../../core/query-params';
import { ProjectLegend } from '../../shared/project-legend';
import { RangeClampNotice } from './range-clamp-notice';
import { formatDuration, formatTotalDuration, projectColor } from '../../shared/ui';

const BASE = API_BASE;

/**
 * Distinct Transaction Groups past which the page stops assuming the instrumentation
 * is fine. A well-instrumented Project has low hundreds of groups; one with
 * unparameterized URLs reaches hundreds of thousands — 5 000 sits safely between.
 */
const HIGH_CARDINALITY_GROUPS = 5_000;

/**
 * Sort options the server whitelists. Values are its own sort keys, so a label added
 * here without a matching one server-side is rejected rather than coerced.
 */
const SORTS: { value: TransactionGroupSort; label: string }[] = [
  { value: 'total_ms', label: 'Sort: total time' },
  { value: 'p95', label: 'Sort: p95' },
  { value: 'p50', label: 'Sort: p50' },
  { value: 'count', label: 'Sort: received' },
];

const DEFAULT_SORT: TransactionGroupSort = 'total_ms';

/**
 * Performance page: Transaction Groups ranked by total time, so fixing the top of
 * the list pays off most. There is no "load more" — the list is a top-N over an
 * aggregate with no key to page on (ADR-0015).
 */
@Component({
  selector: 'app-performance',
  imports: [
    FormsModule,
    RouterLink,
    HlmBadge,
    HlmAlert,
    HlmAlertTitle,
    HlmAlertDescription,
    HlmInput,
    HlmNativeSelect,
    HlmNativeSelectOption,
    HlmTableImports,
    HlmEmpty,
    HlmEmptyHeader,
    HlmEmptyTitle,
    HlmEmptyDescription,
    HlmSpinner,
    ProjectLegend,
    RangeClampNotice,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'flex min-h-0 flex-1 flex-col' },
  templateUrl: './performance.html',
})
export class PerformancePage {
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  readonly filters = inject(GlobalFilters);
  readonly projects = inject(ProjectsStore);

  readonly formatDuration = formatDuration;
  readonly projectColor = projectColor;
  readonly sorts = SORTS;

  private readonly queryParams = toSignal(this.route.queryParams, {
    initialValue: this.route.snapshot.queryParams,
  });

  // Sort and release live in the URL; search is a local signal mirrored to the URL once debounced (ADR-0008 — filter boxes aren't forms).
  readonly sort = computed<TransactionGroupSort>(() => {
    const sort = this.queryParams()['sort'];
    return SORTS.some((option) => option.value === sort) ? sort : DEFAULT_SORT;
  });

  readonly release = computed<string>(() => this.queryParams()['release'] ?? '');

  readonly search = signal<string>(this.route.snapshot.queryParams['query'] ?? '');
  readonly debouncedQuery = debounced(this.search, 300);

  /**
   * The Project whose Releases the filter can offer — a version string is only
   * meaningful within one Project. Available when the view is narrowed to one
   * Project, or when the Installation has only one.
   */
  readonly releaseProject = computed<number | undefined>(() => {
    const selected = this.filters.project();
    if (selected.length === 1) return selected[0];
    if (selected.length > 1) return undefined;
    const all = this.projects.projects();
    return all.length === 1 ? all[0].id : undefined;
  });

  private readonly releaseList = httpResource<Release[]>(
    () => {
      const project = this.releaseProject();
      return project == null ? undefined : { url: `${BASE}/releases`, params: { project } };
    },
    { defaultValue: [] },
  );

  /**
   * Versions to choose from, plus the currently-filtering one if it's not among them
   * (a link shared from another Project, or the list still in flight). Kept visible
   * rather than dropped, since an empty list is ambiguous between "no such release"
   * and "not fetched yet".
   */
  readonly releases = computed<string[]>(() => {
    const versions = this.releaseList.value().map((release) => release.version);
    const chosen = this.release();
    return chosen && !versions.includes(chosen) ? [chosen, ...versions] : versions;
  });

  /** Nothing to choose and nothing to clear — see `releaseProject` for when that happens. */
  readonly releaseFilterUnavailable = computed(
    () => this.releases().length === 0 && this.releaseProject() === undefined,
  );

  private readonly page = httpResource<TransactionGroupPage>(() => ({
    url: `${BASE}/transaction-groups`,
    params: transactionGroupParams({
      project: this.filters.project(),
      environment: this.filters.environments(),
      release: this.release() || undefined,
      query: this.debouncedQuery.value() || undefined,
      sort: this.sort(),
      from: this.filters.from(),
    }),
  }));

  readonly loading = this.page.isLoading;
  readonly error = computed(() => (this.page.error() ? 'Could not load Performance data.' : null));
  readonly groups = computed<TransactionGroup[]>(() =>
    this.page.hasValue() ? this.page.value().groups : [],
  );
  readonly rangeClamped = computed(() =>
    this.page.hasValue() ? this.page.value().range_clamped : false,
  );
  readonly truncated = computed(() => (this.page.hasValue() ? this.page.value().truncated : false));

  /** Every Transaction Group in the window, including the ones the list does not show. */
  readonly distinctGroups = computed(() =>
    this.page.hasValue() ? this.page.value().distinct_groups : 0,
  );

  readonly highCardinality = computed(() => this.distinctGroups() > HIGH_CARDINALITY_GROUPS);

  /** Distinct project ids in the loaded groups, for the color legend. */
  readonly projectIds = computed(() => [...new Set(this.groups().map((g) => g.project_id))]);

  /** The Transaction Group key — (Project, name, op) — which is what identifies a row. */
  readonly groupKey = (group: TransactionGroup): string =>
    JSON.stringify([group.project_id, group.name, group.op]);

  /** Whole counts read better than "12.4k" when they are the denominator of a percentile. */
  readonly formatCount = (count: number): string => count.toLocaleString();

  readonly formatTotal = formatTotalDuration;

  constructor() {
    // Keep the search shareable in the URL, in step with the debounce.
    let lastSynced = this.search();
    effect(() => {
      const query = this.debouncedQuery.value();
      if (query === lastSynced) return;
      lastSynced = query;
      this.syncUrl({ query: query || null });
    });
  }

  onSortChange(event: Event): void {
    const sort = (event.target as HTMLSelectElement).value;
    this.syncUrl({ sort: sort === DEFAULT_SORT ? null : sort });
  }

  onReleaseChange(event: Event): void {
    const release = (event.target as HTMLSelectElement).value;
    this.syncUrl({ release: release || null });
  }

  private syncUrl(params: Record<string, unknown>): void {
    void this.router.navigate([], { queryParams: params, queryParamsHandling: 'merge' });
  }
}
