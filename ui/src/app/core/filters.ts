import { Injectable, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Params, Router } from '@angular/router';
import { computed } from '@angular/core';

/** Global filter context (§9: project + environments + time range), URL-addressable. */
@Injectable({ providedIn: 'root' })
export class GlobalFilters {
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  private readonly queryParams = toSignal(this.route.queryParams, { initialValue: {} as Params });

  readonly project = computed<number | undefined>(() => {
    const raw = this.queryParams()['project'];
    return raw != null && raw !== '' ? Number(raw) : undefined;
  });

  readonly environments = computed<string[]>(() => {
    const raw = this.queryParams()['environment'];
    if (raw == null || raw === '') return [];
    return Array.isArray(raw) ? raw : [raw];
  });

  /** Time range key: 1h | 24h | 7d | 14d | 30d | all */
  readonly range = computed<string>(() => this.queryParams()['range'] ?? '14d');

  /** ISO "from" instant for the current range, or undefined for "all". */
  readonly from = computed<string | undefined>(() => {
    const hours: Record<string, number> = { '1h': 1, '24h': 24, '7d': 168, '14d': 336, '30d': 720 };
    const h = hours[this.range()];
    return h ? new Date(Date.now() - h * 3600_000).toISOString() : undefined;
  });

  setProject(project: number | undefined): void {
    this.merge({ project: project ?? null, environment: null }); // envs are per-project
  }

  setEnvironments(environments: string[]): void {
    this.merge({ environment: environments.length ? environments : null });
  }

  setRange(range: string): void {
    this.merge({ range: range === '14d' ? null : range });
  }

  private merge(params: Params): void {
    void this.router.navigate([], { queryParams: params, queryParamsHandling: 'merge' });
  }
}
