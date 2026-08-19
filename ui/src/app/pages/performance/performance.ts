import {
  ChangeDetectionStrategy,
  Component,
  computed,
  debounced,
  effect,
  inject,
  signal,
  untracked,
} from '@angular/core';
import { httpResource } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
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
import { formatDuration, projectColor } from '../../shared/ui';

const BASE = API_BASE;

/**
 * Distinct Transaction Groups past which the page stops assuming the instrumentation
 * is fine.
 *
 * The two populations this has to tell apart are three orders of magnitude apart, so
 * the exact number matters less than sitting between them. A well-instrumented
 * Project has tens to low hundreds of Transaction Groups — a route per endpoint,
 * times an op or two — and the unfiltered view sums that across every Project, so a
 * large Installation can reach a few thousand honestly. A Project that does not
 * parameterize its URLs emits one Transaction Group per distinct URL and reaches
 * hundreds of thousands in a month. 5 000 is the middle of that gap: high enough
 * that a real Installation does not trip it by growing, low enough that no amount of
 * honest routing gets there.
 */
const HIGH_CARDINALITY_GROUPS = 5_000;

/**
 * The rankings the server whitelists, with the question each one answers. The values
 * are the server's own sort keys: it rejects anything else rather than coercing it, so
 * a label added here without its counterpart in the controller fails loudly.
 */
const SORTS: { value: TransactionGroupSort; label: string }[] = [
  { value: 'total_ms', label: 'Sort: total time' },
  { value: 'p95', label: 'Sort: p95' },
  { value: 'p50', label: 'Sort: p50' },
  { value: 'count', label: 'Sort: received' },
];

const DEFAULT_SORT: TransactionGroupSort = 'total_ms';

/**
 * Performance page: Transaction Groups ranked by the total time each accounts
 * for, so the top of the list is where fixing something pays off most.
 *
 * Total time is the default and not the only question. p95 finds the endpoints with
 * the worst tail, p50 the ones that are uniformly slow, received the hottest paths —
 * so the ranking is a control, and it is the server's whitelist rather than this
 * page's opinion. Beside it, a search narrows by transaction name and a Release
 * filter re-states a group's statistics for one version, which is how a duration
 * change gets attributed to a deploy.
 *
 * There is no "load more" and there will not be: the list is a top-N over an
 * aggregate, which has no key to page on (ADR-0015). The window is capped at 30
 * days, and when the server narrows the global range filter to reach it the page
 * says so — numbers that silently disagree with the filter on screen are the
 * failure this notice exists to prevent.
 *
 * The same principle covers the other two notices. Groups too small to have a
 * percentile are excluded from the ranking, and groups past the limit are cut from
 * it; both exclusions are stated rather than left for the user to infer from a list
 * that looks wrong. The cardinality warning goes further and names the likely cause,
 * because "you have 400 000 Transaction Groups" is not actionable on its own and the
 * action — parameterize the URLs in the SDK's routing integration — is in the user's
 * application, not in Outpost.
 */
@Component({
  selector: 'app-performance',
  imports: [
    FormsModule,
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

  // Sort and Release live in the URL — shareable, and back/forward moves them — while
  // the search box is a local signal for instant feedback, mirrored to the URL once it
  // settles. This is the split the Issues page settled on, and none of the three is a
  // form: a filter box is not a form (ADR-0008).
  readonly sort = computed<TransactionGroupSort>(() => {
    const sort = this.queryParams()['sort'];
    return SORTS.some((option) => option.value === sort) ? sort : DEFAULT_SORT;
  });

  readonly release = computed<string>(() => this.queryParams()['release'] ?? '');

  readonly search = signal(this.route.snapshot.queryParams['query'] ?? '');
  readonly debouncedQuery = debounced(this.search, 300);

  /**
   * The Project whose Releases the filter can offer. A version string only means
   * something inside one Project — the same `1.4.0` belongs to a different codebase
   * next door — which is why `GET /releases` takes a Project and why the Releases page
   * owns a single-Project selector of its own. So the filter is offered when the view
   * is narrowed to one Project, and on an Installation that only has one, where "all
   * Projects" and "that Project" are the same slice.
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

  readonly releases = this.releaseList.value;

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

  /** Total time runs to minutes on a busy group, where `formatDuration` would print "312.4s". */
  readonly formatTotal = (ms: number): string =>
    ms >= 60_000 ? `${(ms / 60_000).toFixed(1)}min` : formatDuration(ms);

  constructor() {
    // Keep the search shareable in the URL, in step with the debounce.
    let lastSynced = this.search();
    effect(() => {
      const query = this.debouncedQuery.value();
      if (query === lastSynced) return;
      lastSynced = query;
      this.syncUrl({ query: query || null });
    });
    // Drop a Release the current Project cannot offer — after switching Project, or
    // from a shared link. Left alone it would be a filter the user can neither see in
    // the control nor clear from it, narrowing the list for no visible reason. Not
    // silent: the select is right there, showing "All releases".
    effect(() => {
      const options = this.releases();
      const chosen = this.release();
      if (!chosen || this.releaseList.isLoading()) return;
      if (!options.some((release) => release.version === chosen)) {
        untracked(() => this.syncUrl({ release: null }));
      }
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
