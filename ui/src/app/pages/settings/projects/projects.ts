import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { form, FormField, FormRoot, pattern, required } from '@angular/forms/signals';
import { firstValueFrom, forkJoin, of, type Observable } from 'rxjs';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmInput } from '@spartan-ng/helm/input';
import { HlmFieldImports } from '@spartan-ng/helm/field';
import { HlmSelectImports } from '@spartan-ng/helm/select';
import { HlmCardImports } from '@spartan-ng/helm/card';

import { Api } from '../../../core/api';
import { Feedback } from '../../../core/feedback';
import { ProjectKey } from '../../../core/models';
import { ProjectsStore } from '../../../core/projects';

/** Projects tab: the Project list, DSN keys, and SDK setup snippets. */
@Component({
  selector: 'app-project-settings',
  imports: [
    FormRoot,
    FormField,
    HlmButton,
    HlmInput,
    HlmFieldImports,
    HlmSelectImports,
    HlmCardImports,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './projects.html',
})
export class ProjectsSettings {
  private readonly api = inject(Api);
  private readonly feedback = inject(Feedback);
  readonly projectsStore = inject(ProjectsStore);

  readonly expandedProject = signal<number | null>(null);
  readonly copied = signal<string | null>(null);

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

  // Create form. The slug doubles as the project name; the 'other' platform
  // maps to a null platform on the DTO (the backend treats it as generic).
  private readonly model = signal({ slug: '', platform: '' });

  readonly projectForm = form(
    this.model,
    (path) => {
      required(path.slug, { message: 'Slug is required.' });
      pattern(path.slug, /^[a-z0-9][a-z0-9-]*$/, {
        message: 'Lowercase letters, numbers, and dashes only.',
      });
      required(path.platform, { message: 'Select a platform.' });
    },
    {
      submission: {
        action: async () => {
          const { slug, platform } = this.model();
          try {
            await firstValueFrom(
              this.api.createProject(slug, slug, platform === 'other' ? null : platform),
            );
            // Clear the values, then reset touched/dirty so the emptied fields
            // don't immediately flash their required errors.
            this.model.set({ slug: '', platform: '' });
            this.projectForm().reset();
            this.projectsStore.reload();
            this.feedback.success('Project created.');
          } catch {
            this.feedback.error('Could not create project — check the slug.');
          }
        },
      },
    },
  );

  // Single source of truth for the platform picker — the template renders the
  // options from this and the trigger label is derived from it. 'other' is a UI
  // sentinel mapped to a null platform on the DTO in the submission action.
  readonly platforms = [
    { value: 'javascript-angular', label: 'Angular' },
    { value: 'java-spring-boot', label: 'Spring Boot' },
    { value: 'other', label: 'Other' },
  ];

  /** Maps a platform value to its display label for the select trigger. */
  readonly platformLabel = (value: string): string =>
    this.platforms.find((platform) => platform.value === value)?.label ?? value;

  toggleProject(projectId: number): void {
    this.expandedProject.set(this.expandedProject() === projectId ? null : projectId);
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
