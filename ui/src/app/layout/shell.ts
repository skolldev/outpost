import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  signal,
} from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmBadge } from '@spartan-ng/helm/badge';
import { HlmSelectImports } from '@spartan-ng/helm/select';

import { Api } from '../core/api';
import { GlobalFilters } from '../core/filters';
import { ProjectsStore } from '../core/projects';
import { Session } from '../core/session';
import { NgIconComponent, provideIcons } from '@ng-icons/core';
import { lucideTowerControl } from '@ng-icons/lucide';

/**
 * App shell (§9): global header with project selector, environment
 * multi-select and time-range picker. Filter state lives in the URL so every
 * view is shareable.
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
    NgIconComponent,
  ],
  providers: [provideIcons({ lucideTowerControl })],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './shell.html',
})
export class Shell {
  private readonly api = inject(Api);
  readonly session = inject(Session);
  readonly filters = inject(GlobalFilters);
  readonly projectsStore = inject(ProjectsStore);

  readonly environments = signal<string[]>([]);

  /**
   * Select value for the project picker. brn-select needs a non-null value to
   * render a selected label, so "all projects" is the `'all'` sentinel rather
   * than undefined. The trigger derives its label from this value via
   * `projectLabel` — not from which <option> the DOM happens to hold — so a
   * project id restored from the URL shows correctly even before the async
   * project list arrives (#58).
   */
  readonly projectValue = computed<number | 'all'>(() => this.filters.project() ?? 'all');

  /** Resolves a project select value to its trigger label. */
  readonly projectLabel = (value: number | 'all'): string =>
    value === 'all' ? 'All projects' : this.projectsStore.name(value);

  /** Time-range options; the single source for both the menu and trigger label. */
  readonly ranges: readonly { value: string; label: string }[] = [
    { value: '1h', label: 'Last hour' },
    { value: '24h', label: 'Last 24 hours' },
    { value: '7d', label: 'Last 7 days' },
    { value: '14d', label: 'Last 14 days' },
    { value: '30d', label: 'Last 30 days' },
    { value: 'all', label: 'All time' },
  ];

  /** Resolves a range key to its trigger label. */
  readonly rangeLabel = (value: string): string =>
    this.ranges.find((r) => r.value === value)?.label ?? value;

  constructor() {
    effect(() => {
      const project = this.filters.project();
      if (project == null) {
        this.environments.set([]);
        return;
      }
      void firstValueFrom(this.api.projectEnvironments(project)).then((envs) =>
        this.environments.set(envs),
      );
    });
  }

  onProjectChange(value: number | 'all' | null | undefined): void {
    this.filters.setProject(value == null || value === 'all' ? undefined : value);
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
