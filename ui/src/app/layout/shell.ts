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
import { HlmNativeSelect, HlmNativeSelectOption } from '@spartan-ng/helm/native-select';

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
    HlmNativeSelect,
    HlmNativeSelectOption,
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

  /** Project id as a string for the native-select value binding ('' = all). */
  readonly projectValue = computed(() => this.filters.project()?.toString() ?? '');

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

  onProjectChange(event: Event): void {
    const value = (event.target as HTMLSelectElement).value;
    this.filters.setProject(value === '' ? undefined : Number(value));
  }

  onRangeChange(event: Event): void {
    this.filters.setRange((event.target as HTMLSelectElement).value);
  }

  toggleEnvironment(env: string): void {
    const current = this.filters.environments();
    this.filters.setEnvironments(
      current.includes(env) ? current.filter((e) => e !== env) : [...current, env],
    );
  }
}
