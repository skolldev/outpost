import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { httpResource } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmInput } from '@spartan-ng/helm/input';
import { HlmLabel } from '@spartan-ng/helm/label';
import { HlmNativeSelect, HlmNativeSelectOption } from '@spartan-ng/helm/native-select';
import { HlmCardImports } from '@spartan-ng/helm/card';

import { Api } from '../../core/api';
import { API_BASE } from '../../core/api-base';
import { ProjectKey } from '../../core/models';
import { ProjectsStore } from '../../core/projects';

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

  // DSN keys for the expanded Project; no fetch while collapsed (undefined URL).
  private readonly keysResource = httpResource<ProjectKey[]>(
    () => {
      const id = this.expandedProject();
      return id === null ? undefined : `${API_BASE}/projects/${id}/keys`;
    },
    { defaultValue: [] },
  );
  readonly keys = this.keysResource.value;

  readonly activeDsn = computed<string | null>(
    () => this.keys().find((key) => key.is_active)?.dsn ?? null,
  );

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
