import { ChangeDetectionStrategy, Component, effect, inject, untracked } from '@angular/core';
import { httpResource } from '@angular/common/http';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmBadge } from '@spartan-ng/helm/badge';
import { HlmSelectImports } from '@spartan-ng/helm/select';
import { HlmComboboxImports } from '@spartan-ng/helm/combobox';
import { HlmDropdownMenuImports } from '@spartan-ng/helm/dropdown-menu';

import { API_BASE } from '../core/api-base';
import { GlobalFilters } from '../core/filters';
import { ProjectsStore } from '../core/projects';
import { Session } from '../core/session';
import { NgIconComponent, provideIcons } from '@ng-icons/core';
import { lucideTowerControl } from '@ng-icons/lucide';

/**
 * App shell: global header with a multi-select project filter, a cross-project
 * environment-intersection bar and a time-range picker. Filter state lives in the
 * URL so every view is shareable.
 */
@Component({
  selector: 'app-shell',
  imports: [
    RouterOutlet,
    RouterLink,
    RouterLinkActive,
    HlmButton,
    HlmBadge,
    HlmSelectImports,
    HlmComboboxImports,
    HlmDropdownMenuImports,
    NgIconComponent,
  ],
  providers: [provideIcons({ lucideTowerControl })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './shell.html',
})
export class Shell {
  readonly session = inject(Session);
  readonly filters = inject(GlobalFilters);
  readonly projectsStore = inject(ProjectsStore);

  /**
   * The environment bar shows the intersection of Environment Names across the
   * in-scope Projects (ADR 0009) — the selected set, or all Projects when the
   * selection is empty (the `project` param is then omitted). Usually empty on
   * the default all-Projects view; that is intended.
   */
  private readonly environmentsResource = httpResource<string[]>(
    () => ({
      url: `${API_BASE}/projects/environments`,
      params: { project: this.filters.project() },
    }),
    { defaultValue: [] },
  );

  readonly environments = this.environmentsResource.value;

  /**
   * Whether the user has changed the in-scope Projects. Until then the active
   * environment filter reflects the URL, not a selection, and must not prune.
   */
  private selectionChanged = false;

  /** Resolves a project id to its chip/option label (falls back while loading). */
  readonly projectLabel = (id: number): string => this.projectsStore.name(id);

  /** Time-range options; the single source for both the menu and trigger label. */
  readonly ranges: readonly { value: string; label: string }[] = [
    { value: '1h', label: 'Last hour' },
    { value: '24h', label: 'Last 24 hours' },
    { value: '7d', label: 'Last 7 days' },
    { value: '14d', label: 'Last 14 days' },
    { value: '30d', label: 'Last 30 days' },
    { value: 'all', label: 'All time' },
  ];

  readonly rangeLabel = (value: string): string =>
    this.ranges.find((r) => r.value === value)?.label ?? value;

  constructor() {
    // When the in-scope Projects change, prune the active environment filter to
    // the names still present in the new intersection rather than clearing it,
    // preserving intent whenever it stays valid (ADR 0009). Only a *resolved*
    // intersection prunes — a loading/reloading value is stale and an errored one
    // falls back to [], either of which would wrongly blank a valid filter.
    // Pruning is gated on a user selection change rather than on the first
    // response, so a shared/reloaded URL's environment filter is preserved while
    // a change made before (or instead of) that first response still prunes.
    effect(() => {
      if (this.environmentsResource.status() !== 'resolved') return;
      const intersection = this.environmentsResource.value();
      untracked(() => {
        if (!this.selectionChanged) return;
        const current = this.filters.environments();
        const pruned = current.filter((env) => intersection.includes(env));
        if (pruned.length !== current.length) this.filters.setEnvironments(pruned);
      });
    });
  }

  onProjectsChange(ids: number[] | null | undefined): void {
    this.selectionChanged = true;
    this.filters.setProjects(ids ?? []);
  }

  onRangeChange(value: string | null | undefined): void {
    if (value != null) this.filters.setRange(value);
  }

  toggleEnvironment(env: string): void {
    const current = this.filters.environments();
    this.filters.setEnvironments(
      current.includes(env) ? current.filter((e) => e !== env) : [...current, env],
    );
  }
}
