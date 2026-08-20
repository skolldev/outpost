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
 * Transaction Group detail: the statistics of the row that was clicked, and the two
 * links out of them.
 *
 * Aggregate statistics say *that* a Transaction Group is slow. They cannot say *why* —
 * for that the user needs a real request's span waterfall, which the Traces page
 * already renders. This view is the bridge, and it offers **two** ways across on
 * purpose: the slowest Traces alone would send every investigation chasing a single
 * outlier — a GC pause, a cold start — where diffing a slow request against a typical
 * one shows what actually differs.
 *
 * The identity travels in query params rather than a path segment, because transaction
 * names contain slashes. `project` is one of them, and it is the same param the global
 * Project filter uses: a Transaction Group belongs to exactly one Project, so opening
 * one *is* narrowing to that Project, and two params meaning "which Project" could
 * disagree. **An absent `op` means the group whose op is null** — (project, name, op)
 * is the whole key, so "any op" would name a set of groups rather than one.
 *
 * Between the two sits the chart (#163), which is what turns a statistic into a report:
 * a p95 on its own is a ranking, while a p95 that stepped up on Tuesday names the day
 * to look at. It reads the bucketed series the same response carries, so it describes
 * exactly the Transactions the header above it does.
 *
 * The window is the leaderboard's, capped at 30 days and echoed by the server; when it
 * was narrowed to reach that cap this page says so, for the same reason the leaderboard
 * does — a header that quietly covered a different window than the row it was opened
 * from would disagree with the number the user just clicked.
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
   * The Project the group belongs to. Read from the URL rather than from
   * `GlobalFilters.project()`, which is a list: this is an identity, and the first of a
   * multi-select is a guess. Selecting other Projects in the shell while here therefore
   * asks for this group in the first of them, which either finds it or says it is not
   * there — both honest answers, and neither one a page that silently changed which
   * Transaction Group it is describing.
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
   * The upper edge of the window the server answered over, which is what the chart's
   * time axis spans. Read from the response rather than from `GlobalFilters`, which
   * holds a relative range and no upper bound at all — the server resolves "now" once,
   * and an axis drawn to a second "now" would end somewhere the data does not.
   */
  readonly windowTo = computed<string | undefined>(() =>
    this.detail.hasValue() ? this.detail.value().to : undefined,
  );

  /**
   * A key that names no Transaction Group in this window is a 404, and it is the
   * expected outcome of a shared link to an endpoint that has since gone quiet — not a
   * failure. Only the rest is an error the user can do nothing about.
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
   * The Traces page filtered to this group's slow Traces: everything at or above its
   * p95, which is the tail the percentiles could only point at.
   *
   * `name` and `op` are cleared explicitly. Every other param is merged so the Traces
   * page opens on the slice the user was just reading — Project, Environment Name and
   * time range — but those two identify a Transaction Group, which means nothing there,
   * and merged params outlive the page that set them.
   *
   * Known imprecision, accepted in #162: the Traces page matches names by
   * case-insensitive substring and cannot pin an op, so a link for `GET /orders` also
   * surfaces `GET /orders/{id}`.
   */
  readonly slowTracesParams = computed<Params>(() => ({
    ...this.tracesLink(),
    min_duration: this.group()?.p95_ms ?? 0,
    max_duration: null,
  }));

  /**
   * And to its typical Traces: the band from the median up to where the tail begins.
   *
   * Bounded on both sides because "typical" has to exclude the fast end as well as the
   * slow one — a cache hit or a 304 diffs against a slow request no more usefully than
   * another slow request does. The median is the fastest Trace this can return, which
   * is what makes it the typical one.
   */
  readonly typicalTracesParams = computed<Params>(() => ({
    ...this.tracesLink(),
    min_duration: this.group()?.p50_ms ?? 0,
    max_duration: this.group()?.p95_ms ?? 0,
  }));

  readonly formatDuration = formatDuration;

  /**
   * Back to the list this was opened from, with the key cleared.
   *
   * Everything else merges — the leaderboard is meant to reopen on the slice the user
   * left it in — but `name` and `op` identify one Transaction Group and nothing on that
   * page reads them, so carrying them back would leave a URL describing a view it is
   * not showing.
   */
  readonly backToListParams: Params = { name: null, op: null };

  private tracesLink(): Params {
    return {
      query: this.name(),
      name: null,
      op: null,
      // The server answers an all-time request over its 30-day ceiling. Override the
      // merged global range so Traces searches the same duration window as the
      // percentile thresholds were computed from.
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
