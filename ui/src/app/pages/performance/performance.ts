import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { httpResource } from '@angular/common/http';
import { HlmBadge } from '@spartan-ng/helm/badge';
import { HlmAlert, HlmAlertDescription, HlmAlertTitle } from '@spartan-ng/helm/alert';
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
import { TransactionGroup, TransactionGroupPage } from '../../core/models';
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
 * Performance page: Transaction Groups ranked by the total time each accounts
 * for, so the top of the list is where fixing something pays off most.
 *
 * There is no sort control and no "load more" here, and only the second is
 * permanent: the list is a top-N over an aggregate, which has no key to page on
 * (ADR-0015). The window is capped at 30 days, and when the server narrows the
 * global range filter to reach it the page says so — numbers that silently
 * disagree with the filter on screen are the failure this notice exists to
 * prevent.
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
    HlmBadge,
    HlmAlert,
    HlmAlertTitle,
    HlmAlertDescription,
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
  readonly filters = inject(GlobalFilters);
  readonly projects = inject(ProjectsStore);

  readonly formatDuration = formatDuration;
  readonly projectColor = projectColor;

  private readonly page = httpResource<TransactionGroupPage>(() => ({
    url: `${BASE}/transaction-groups`,
    params: transactionGroupParams({
      project: this.filters.project(),
      environment: this.filters.environments(),
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
}
