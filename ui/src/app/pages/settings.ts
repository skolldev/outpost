import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';

import { Api } from '../core/api';
import { ApiToken, AppUser, Project, ProjectKey } from '../core/models';
import { Session } from '../core/session';

/** Settings (§9 page 6): projects & DSNs, sentry-cli API tokens, users. Admin-only mutations. */
@Component({
  selector: 'app-settings',
  imports: [DatePipe, FormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './settings.html',
})
export class SettingsPage {
  private readonly api = inject(Api);
  readonly session = inject(Session);

  // API tokens are admin-only end to end, so members don't get the tab.
  readonly tabs = computed<('projects' | 'tokens' | 'users')[]>(() =>
    this.session.isAdmin() ? ['projects', 'tokens', 'users'] : ['projects', 'users'],
  );
  readonly activeTab = signal<'projects' | 'tokens' | 'users'>('projects');

  readonly projects = signal<Project[]>([]);
  readonly keys = signal<ProjectKey[]>([]);
  readonly users = signal<AppUser[]>([]);
  readonly tokens = signal<ApiToken[]>([]);
  readonly createdToken = signal<ApiToken | null>(null);
  readonly expandedProject = signal<number | null>(null);
  readonly copied = signal<string | null>(null);
  readonly error = signal<string | null>(null);

  newSlug = '';
  newPlatform = 'javascript-angular';
  newEmail = '';
  newPassword = '';
  newRole = 'member';
  newTokenName = '';

  constructor() {
    void this.reloadProjects();
    if (this.session.isAdmin()) {
      void firstValueFrom(this.api.users()).then((users) => this.users.set(users));
      void firstValueFrom(this.api.tokens()).then((tokens) => this.tokens.set(tokens));
    }
  }

  activeDsn(): string | null {
    return this.keys().find((k) => k.is_active)?.dsn ?? null;
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

  async toggleProject(project: Project): Promise<void> {
    if (this.expandedProject() === project.id) {
      this.expandedProject.set(null);
      return;
    }
    this.expandedProject.set(project.id);
    this.keys.set(await firstValueFrom(this.api.projectKeys(project.id)));
  }

  async createProject(): Promise<void> {
    this.error.set(null);
    try {
      await firstValueFrom(
        this.api.createProject(this.newSlug, this.newSlug, this.newPlatform || null),
      );
      this.newSlug = '';
      await this.reloadProjects();
    } catch {
      this.error.set('Could not create project — check the slug.');
    }
  }

  async addKey(projectId: number): Promise<void> {
    await firstValueFrom(this.api.createProjectKey(projectId));
    this.keys.set(await firstValueFrom(this.api.projectKeys(projectId)));
  }

  async setKeyActive(projectId: number, key: ProjectKey): Promise<void> {
    await firstValueFrom(this.api.setKeyActive(projectId, key.id, !key.is_active));
    this.keys.set(await firstValueFrom(this.api.projectKeys(projectId)));
  }

  async createToken(): Promise<void> {
    this.error.set(null);
    try {
      const created = await firstValueFrom(this.api.createToken(this.newTokenName));
      this.createdToken.set(created);
      this.newTokenName = '';
      this.tokens.set(await firstValueFrom(this.api.tokens()));
    } catch {
      this.error.set('Could not create token.');
    }
  }

  async deleteToken(token: ApiToken): Promise<void> {
    await firstValueFrom(this.api.deleteToken(token.id));
    if (this.createdToken()?.id === token.id) {
      this.createdToken.set(null);
    }
    this.tokens.set(await firstValueFrom(this.api.tokens()));
  }

  cliSnippet(token: string): string {
    return `# CI: upload source maps after ng build
export SENTRY_URL=${location.origin}
export SENTRY_AUTH_TOKEN=${token}
export SENTRY_ORG=outpost
export SENTRY_PROJECT=<project-slug>
sentry-cli sourcemaps inject ./dist/<app>/browser
sentry-cli sourcemaps upload --release "<app>@$VERSION" ./dist/<app>/browser`;
  }

  async createUser(): Promise<void> {
    this.error.set(null);
    try {
      await firstValueFrom(this.api.createUser(this.newEmail, this.newPassword, this.newRole));
      this.newEmail = '';
      this.newPassword = '';
      this.users.set(await firstValueFrom(this.api.users()));
    } catch {
      this.error.set('Could not create user.');
    }
  }

  copy(text: string): void {
    void navigator.clipboard.writeText(text).then(() => {
      this.copied.set(text);
      setTimeout(() => this.copied.set(null), 1500);
    });
  }

  private async reloadProjects(): Promise<void> {
    this.projects.set(await firstValueFrom(this.api.projects()));
  }
}
