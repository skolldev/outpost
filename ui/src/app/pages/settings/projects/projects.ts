import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { firstValueFrom, forkJoin, of, type Observable } from 'rxjs';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmInput } from '@spartan-ng/helm/input';
import { HlmLabel } from '@spartan-ng/helm/label';
import { HlmNativeSelect, HlmNativeSelectOption } from '@spartan-ng/helm/native-select';
import { HlmCardImports } from '@spartan-ng/helm/card';

import { Api } from '../../../core/api';
import { ProjectKey } from '../../../core/models';
import { ProjectsStore } from '../../../core/projects';

/** Projects tab: the Project list, DSN keys, and SDK setup snippets. */
@Component({
  selector: 'app-project-settings',
  imports: [
    FormsModule,
    HlmButton,
    HlmInput,
    HlmLabel,
    HlmNativeSelect,
    HlmNativeSelectOption,
    ...HlmCardImports,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './projects.html',
})
export class ProjectsSettings {
  private readonly api = inject(Api);
  readonly projectsStore = inject(ProjectsStore);

  readonly expandedProject = signal<number | null>(null);
  readonly copied = signal<string | null>(null);
  readonly error = signal<string | null>(null);

  // DSN keys for every project, fetched together when the tab opens and after
  // any rotate/revoke. Installations are small (a handful of projects), so one
  // request per project up front costs less than re-fetching on each expand and
  // lets the template render each project's keys inline. Re-runs when the
  // project list changes (e.g. after a create).
  private readonly keysResource = rxResource({
    params: () => this.projectsStore.projects().map((project) => project.id),
    stream: ({ params: ids }) => {
      if (ids.length === 0) return of<Record<number, ProjectKey[]>>({});
      const requests: Record<number, Observable<ProjectKey[]>> = {};
      for (const id of ids) requests[id] = this.api.projectKeys(id);
      return forkJoin(requests);
    },
    defaultValue: {},
  });
  readonly keysByProject = this.keysResource.value;

  readonly activeDsn = computed<string | null>(() => {
    const id = this.expandedProject();
    if (id === null) return null;
    return this.keysByProject()[id]?.find((key) => key.is_active)?.dsn ?? null;
  });

  newSlug = '';
  newPlatform = 'javascript-angular';

  toggleProject(projectId: number): void {
    this.expandedProject.set(this.expandedProject() === projectId ? null : projectId);
  }

  async createProject(): Promise<void> {
    this.error.set(null);
    try {
      await firstValueFrom(
        this.api.createProject(this.newSlug, this.newSlug, this.newPlatform || null),
      );
      this.newSlug = '';
      this.projectsStore.reload();
    } catch {
      this.error.set('Could not create project — check the slug.');
    }
  }

  async addKey(projectId: number): Promise<void> {
    await firstValueFrom(this.api.createProjectKey(projectId));
    this.keysResource.reload();
  }

  async setKeyActive(projectId: number, key: ProjectKey): Promise<void> {
    await firstValueFrom(this.api.setKeyActive(projectId, key.id, !key.is_active));
    this.keysResource.reload();
  }

  angularSnippet(dsn: string): string {
    return `Sentry.init({
  dsn: "${dsn}",
  environment: "prod",                  // dev | qa | prod
  release: "my-app@" + APP_VERSION,
  tracesSampleRate: 1.0,
  integrations: [Sentry.browserTracingIntegration()],
  tracePropagationTargets: ["https://api.example.com"],
  enableLogs: true,
});`;
  }

  springSnippet(dsn: string): string {
    return `sentry:
  dsn: ${dsn}
  environment: prod
  release: my-app@\${APP_VERSION}
  traces-sample-rate: 1.0
  logs.enabled: true`;
  }

  copy(text: string): void {
    void navigator.clipboard.writeText(text).then(() => {
      this.copied.set(text);
      setTimeout(() => this.copied.set(null), 1500);
    });
  }
}
