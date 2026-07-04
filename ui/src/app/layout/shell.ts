import { ChangeDetectionStrategy, Component, effect, inject, signal } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { firstValueFrom } from 'rxjs';

import { Api } from '../core/api';
import { GlobalFilters } from '../core/filters';
import { Project } from '../core/models';
import { Session } from '../core/session';

/**
 * App shell (§9): global header with project selector, environment
 * multi-select and time-range picker. Filter state lives in the URL so every
 * view is shareable.
 */
@Component({
  selector: 'app-shell',
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './shell.html',
})
export class Shell {
  private readonly api = inject(Api);
  readonly session = inject(Session);
  readonly filters = inject(GlobalFilters);

  readonly projects = signal<Project[]>([]);
  readonly environments = signal<string[]>([]);

  constructor() {
    void firstValueFrom(this.api.projects()).then((projects) => this.projects.set(projects));
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
