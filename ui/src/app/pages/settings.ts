import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmInput } from '@spartan-ng/helm/input';
import { HlmLabel } from '@spartan-ng/helm/label';
import { HlmNativeSelect, HlmNativeSelectOption } from '@spartan-ng/helm/native-select';
import { HlmCard } from '@spartan-ng/helm/card';
import { HlmAlert, HlmAlertTitle, HlmAlertDescription } from '@spartan-ng/helm/alert';

import { Api } from '../core/api';
import {
  ApiToken,
  AppUser,
  Project,
  ProjectKey,
  UptimeMonitor,
  UptimeTestResult,
} from '../core/models';
import { Session } from '../core/session';

/** Settings (§9 page 6): projects & DSNs, sentry-cli API tokens, users. Admin-only mutations. */
@Component({
  selector: 'app-settings',
  imports: [
    DatePipe,
    FormsModule,
    HlmButton,
    HlmInput,
    HlmLabel,
    HlmNativeSelect,
    HlmNativeSelectOption,
    HlmCard,
    HlmAlert,
    HlmAlertTitle,
    HlmAlertDescription,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './settings.html',
})
export class SettingsPage {
  private readonly api = inject(Api);
  readonly session = inject(Session);

  // API tokens and uptime monitors are admin-managed, so members don't get those tabs.
  readonly tabs = computed<('projects' | 'uptime' | 'tokens' | 'users')[]>(() =>
    this.session.isAdmin() ? ['projects', 'uptime', 'tokens', 'users'] : ['projects', 'users'],
  );
  readonly activeTab = signal<'projects' | 'uptime' | 'tokens' | 'users'>('projects');

  readonly projects = signal<Project[]>([]);
  readonly keys = signal<ProjectKey[]>([]);
  readonly users = signal<AppUser[]>([]);
  readonly tokens = signal<ApiToken[]>([]);
  readonly createdToken = signal<ApiToken | null>(null);
  readonly expandedProject = signal<number | null>(null);
  readonly copied = signal<string | null>(null);
  readonly error = signal<string | null>(null);

  readonly monitors = signal<UptimeMonitor[]>([]);
  readonly monitorEnvs = signal<string[]>([]);
  readonly testResult = signal<UptimeTestResult | 'pending' | null>(null);
  readonly editingMonitorId = signal<number | null>(null);

  newSlug = '';
  newPlatform = 'javascript-angular';
  newEmail = '';
  newPassword = '';
  newRole = 'member';
  newTokenName = '';
  newMonitorProjectId = '';
  newMonitorEnv = '';
  newMonitorUrl = '';
  newMonitorInterval = '60';
  newMonitorTimeout = 10;

  constructor() {
    void this.reloadProjects();
    if (this.session.isAdmin()) {
      void firstValueFrom(this.api.users()).then((users) => this.users.set(users));
      void firstValueFrom(this.api.tokens()).then((tokens) => this.tokens.set(tokens));
      void this.reloadMonitors();
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

  async onMonitorProjectChange(): Promise<void> {
    this.newMonitorEnv = '';
    this.monitorEnvs.set([]);
    const projectId = Number(this.newMonitorProjectId);
    if (!projectId) return;
    this.monitorEnvs.set(await firstValueFrom(this.api.projectEnvironments(projectId)));
  }

  async saveMonitor(): Promise<void> {
    this.error.set(null);
    const body = {
      project_id: Number(this.newMonitorProjectId),
      environment: this.newMonitorEnv,
      url: this.newMonitorUrl,
      interval_seconds: Number(this.newMonitorInterval),
      timeout_seconds: Number(this.newMonitorTimeout),
    };
    try {
      const editing = this.editingMonitorId();
      if (editing === null) {
        await firstValueFrom(this.api.createUptimeMonitor(body));
      } else {
        await firstValueFrom(this.api.updateUptimeMonitor(editing, body));
      }
      this.resetMonitorForm();
      await this.reloadMonitors();
    } catch {
      this.error.set('Could not save monitor — check the URL.');
    }
  }

  async editMonitor(monitor: UptimeMonitor): Promise<void> {
    this.editingMonitorId.set(monitor.id);
    this.newMonitorProjectId = String(monitor.project_id);
    this.newMonitorUrl = monitor.url;
    this.newMonitorInterval = String(monitor.interval_seconds);
    this.newMonitorTimeout = monitor.timeout_seconds;
    this.testResult.set(null);
    this.monitorEnvs.set(await firstValueFrom(this.api.projectEnvironments(monitor.project_id)));
    this.newMonitorEnv = monitor.environment;
  }

  cancelMonitorEdit(): void {
    this.resetMonitorForm();
  }

  async deleteMonitor(monitor: UptimeMonitor): Promise<void> {
    await firstValueFrom(this.api.deleteUptimeMonitor(monitor.id));
    if (this.editingMonitorId() === monitor.id) {
      this.resetMonitorForm();
    }
    await this.reloadMonitors();
  }

  async testMonitor(): Promise<void> {
    this.testResult.set('pending');
    try {
      this.testResult.set(
        await firstValueFrom(
          this.api.testUptimeMonitor(this.newMonitorUrl, Number(this.newMonitorTimeout)),
        ),
      );
    } catch {
      this.testResult.set(null);
      this.error.set('Test request failed — check the URL.');
    }
  }

  intervalLabel(seconds: number): string {
    return seconds < 60 ? `${seconds}s` : `${seconds / 60}m`;
  }

  private resetMonitorForm(): void {
    this.editingMonitorId.set(null);
    this.newMonitorProjectId = '';
    this.newMonitorEnv = '';
    this.newMonitorUrl = '';
    this.newMonitorInterval = '60';
    this.newMonitorTimeout = 10;
    this.monitorEnvs.set([]);
    this.testResult.set(null);
  }

  private async reloadMonitors(): Promise<void> {
    this.monitors.set(await firstValueFrom(this.api.uptimeMonitors()));
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
