import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { HttpErrorResponse, httpResource } from '@angular/common/http';
import { ActivatedRoute, Params, RouterLink } from '@angular/router';
import { toSignal } from '@angular/core/rxjs-interop';
import { HlmBadge } from '@spartan-ng/helm/badge';
import { HlmAlert, HlmAlertDescription, HlmAlertTitle } from '@spartan-ng/helm/alert';
import { HlmButton } from '@spartan-ng/helm/button';
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
  TransactionGroup,
  TransactionGroupDetail,
  TransactionGroupDetailNotFound,
  TransactionGroupTrend,
} from '../../core/models';
import { ProjectsStore } from '../../core/projects';
import { transactionGroupDetailParams } from '../../core/query-params';
import { DurationTrendChart } from '../../shared/duration-trend';
import { formatDuration, formatTotalDuration, projectColor } from '../../shared/ui';
import { RangeClampNotice } from './range-clamp-notice';

const BASE = API_BASE;

/** One statistic on the header, so the template loops rather than repeating a cell nine times. */
interface Statistic {
  label: string;
  value: string;
  hint?: string;
}

/**
 * Transaction Group detail: the clicked row's statistics, plus links to its slow and
 * typical Traces for investigating why. Identity travels in query params, not a path
 * segment, since transaction names can contain slashes.
 */
@Component({
  selector: 'app-transaction-group-detail',
  imports: [
    RouterLink,
    HlmBadge,
    HlmAlert,
    HlmAlertTitle,
    HlmAlertDescription,
    HlmButton,
    HlmEmpty,
    HlmEmptyHeader,
    HlmEmptyTitle,
    HlmEmptyDescription,
    HlmSpinner,
    RangeClampNotice,
    DurationTrendChart,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'flex min-h-0 flex-1 flex-col' },
  templateUrl: './transaction-group-detail.html',
})
export class TransactionGroupDetailPage {
  private readonly route = inject(ActivatedRoute);
  readonly filters = inject(GlobalFilters);
  readonly projects = inject(ProjectsStore);

  readonly projectColor = projectColor;

  private readonly queryParams = toSignal(this.route.queryParams, {
    initialValue: this.route.snapshot.queryParams,
  });

  readonly name = computed<string>(() => this.first(this.queryParams()['name']) ?? '');

  /** Absent, blank and missing all mean the same group: the one whose op is null. */
  readonly op = computed<string | null>(() => this.first(this.queryParams()['op']) || null);

  /**
   * The Project the group belongs to, read from the URL rather than
   * `GlobalFilters.project()` — that's a multi-select list, and this is an identity,
   * not a guess at one.
   */
  readonly project = computed<number | undefined>(() => {
    const raw = this.first(this.queryParams()['project']);
    const id = raw == null || raw === '' ? NaN : Number(raw);
    return Number.isNaN(id) ? undefined : id;
  });

  private readonly release = computed<string>(
    () => this.first(this.queryParams()['release']) ?? '',
  );

  private readonly detail = httpResource<TransactionGroupDetail>(() => {
    const project = this.project();
    if (project == null || !this.name()) return undefined;
    return {
      url: `${BASE}/transaction-groups/detail`,
      params: transactionGroupDetailParams({
        project,
        name: this.name(),
        op: this.op(),
        environment: this.filters.environments(),
        release: this.release() || undefined,
        from: this.filters.from(),
      }),
    };
  });

  readonly loading = this.detail.isLoading;

  readonly group = computed<TransactionGroup | null>(() =>
    this.detail.hasValue() ? this.detail.value().group : null,
  );

  readonly trend = computed<TransactionGroupTrend | undefined>(() =>
    this.detail.hasValue() ? this.detail.value().trend : undefined,
  );

  /**
   * Upper edge of the window the server answered over — the chart's axis span. Read
   * from the response, not `GlobalFilters` (no upper bound), so the axis matches the
   * moment the server resolved "now".
   */
  readonly windowTo = computed<string | undefined>(() =>
    this.detail.hasValue() ? this.detail.value().to : undefined,
  );

  /**
   * A 404 here is expected — a shared link to an endpoint that's gone quiet — not a
   * failure; only other statuses are shown as an error.
   */
  readonly notFound = computed(() => this.status() === 404);

  readonly error = computed(() => {
    const status = this.status();
    if (status == null || status === 404) return null;
    return 'Could not load this Transaction Group.';
  });

  /** Missing from the URL entirely — a hand-edited or truncated link. */
  readonly incompleteLink = computed(() => this.project() == null || !this.name());

  readonly rangeClamped = computed(() => {
    if (this.detail.hasValue()) return this.detail.value().range_clamped;
    return this.notFoundBody()?.range_clamped ?? false;
  });

  /** The header, in the leaderboard's own column order so the eye lands in the same places. */
  readonly statistics = computed<Statistic[]>(() => {
    const group = this.group();
    if (!group) return [];
    return [
      { label: 'Received', value: group.count.toLocaleString(), hint: 'Transactions' },
      { label: 'Total time', value: formatTotalDuration(group.total_ms) },
      { label: 'p50', value: formatDuration(group.p50_ms) },
      { label: 'p95', value: formatDuration(group.p95_ms) },
      { label: 'p99', value: formatDuration(group.p99_ms) },
      { label: 'Avg', value: formatDuration(group.avg_ms) },
      { label: 'Max', value: formatDuration(group.max_ms) },
    ];
  });

  /**
   * Traces page filtered to this group's slow Traces (at or above its p95); `name`/`op`
   * are cleared and the rest of the params merge in. Known imprecision (#162): the
   * Traces page matches names by case-insensitive substring and can't pin an op, so
   * this also surfaces e.g. `GET /orders/{id}` for a `GET /orders` group.
   */
  readonly slowTracesParams = computed<Params>(() => ({
    ...this.tracesLink(),
    min_duration: this.group()?.p95_ms ?? 0,
    max_duration: null,
  }));

  /**
   * Traces page filtered to this group's typical Traces: the band from p50 to p95,
   * excluding both the slow tail and fast outliers (a cache hit diffs no more usefully
   * than another slow request).
   */
  readonly typicalTracesParams = computed<Params>(() => ({
    ...this.tracesLink(),
    min_duration: this.group()?.p50_ms ?? 0,
    max_duration: this.group()?.p95_ms ?? 0,
  }));

  readonly formatDuration = formatDuration;

  /**
   * Back to the list this was opened from; `name`/`op` identify this one group and
   * mean nothing there, so they're cleared while everything else merges.
   */
  readonly backToListParams: Params = { name: null, op: null };

  private tracesLink(): Params {
    return {
      query: this.name(),
      name: null,
      op: null,
      // Override the merged range to 30d so Traces searches the same window the percentiles were computed from.
      ...(this.rangeClamped() ? { range: '30d' } : {}),
    };
  }

  private status(): number | undefined {
    const error = this.detail.error() as HttpErrorResponse | undefined;
    return error?.status;
  }

  private notFoundBody(): TransactionGroupDetailNotFound | null {
    const error = this.detail.error() as HttpErrorResponse | undefined;
    if (error?.status !== 404 || !error.error || typeof error.error !== 'object') return null;
    return error.error as TransactionGroupDetailNotFound;
  }

  private first(raw: unknown): string | undefined {
    if (Array.isArray(raw)) return raw[0] as string | undefined;
    return raw as string | undefined;
  }
}
