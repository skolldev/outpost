import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmInput } from '@spartan-ng/helm/input';
import { HlmLabel } from '@spartan-ng/helm/label';
import { HlmNativeSelect, HlmNativeSelectOption } from '@spartan-ng/helm/native-select';
import { HlmCardImports } from '@spartan-ng/helm/card';
import { HlmAlert, HlmAlertTitle, HlmAlertDescription } from '@spartan-ng/helm/alert';
import { HlmCheckbox } from '@spartan-ng/helm/checkbox';
import { HlmFieldImports } from '@spartan-ng/helm/field';
import { HlmSpinner } from '@spartan-ng/helm/spinner';

import { Api } from '../core/api';
import {
  ApiToken,
  AppUser,
  DataRetentionSetting,
  NotificationChannel,
  NotificationChannelType,
  NotificationTrigger,
  Project,
  ProjectKey,
  RetentionDays,
  UptimeMonitor,
  UptimeTestResult,
} from '../core/models';
import { Session } from '../core/session';

type SettingsTab = 'projects' | 'uptime' | 'notifications' | 'data-retention' | 'tokens' | 'users';
type RetentionDaysValue = '30' | '60' | '90';

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
    ...HlmCardImports,
    HlmAlert,
    HlmAlertTitle,
    HlmAlertDescription,
    HlmCheckbox,
    ...HlmFieldImports,
    HlmSpinner,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './settings.html',
})
export class SettingsPage {
  private readonly api = inject(Api);
  readonly session = inject(Session);

  // Installation settings and operational resources are admin-managed.
  readonly tabs = computed<SettingsTab[]>(() =>
    this.session.isAdmin()
      ? ['projects', 'uptime', 'notifications', 'data-retention', 'tokens', 'users']
      : ['projects', 'users'],
  );
  readonly activeTab = signal<SettingsTab>('projects');

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

  readonly channels = signal<NotificationChannel[]>([]);
  readonly editingChannelId = signal<number | null>(null);
  readonly confirmDeleteChannelId = signal<number | null>(null);
  readonly channelTriggerOptions: { value: NotificationTrigger; label: string }[] = [
    { value: 'new_issue', label: 'New issue' },
    { value: 'incident_started', label: 'Incident started' },
    { value: 'incident_resolved', label: 'Incident resolved' },
  ];

  readonly retentionDurations: RetentionDaysValue[] = ['30', '60', '90'];
  readonly retentionLoading = signal(false);
  readonly retentionSaving = signal(false);
  readonly retentionError = signal<string | null>(null);
  readonly retentionSuccess = signal<string | null>(null);
  retentionEnabled = false;
  retentionDays: RetentionDaysValue = '90';

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

  newChannelName = '';
  newChannelType: NotificationChannelType = 'teams';
  newChannelUrl = '';
  // Round-trips the enabled flag through the edit form (no visible control —
  // enable/disable is a per-row toggle in the list). New channels start enabled.
  newChannelEnabled = true;
  channelTriggers: Record<NotificationTrigger, boolean> = {
    new_issue: false,
    incident_started: false,
    incident_resolved: false,
  };
  // Keyed by project id; a truthy entry means the channel is scoped to it.
  channelProjectSelected: Record<number, boolean> = {};
  newChannelEnvironments = '';

  constructor() {
    void this.reloadProjects();
    if (this.session.isAdmin()) {
      void firstValueFrom(this.api.users()).then((users) => this.users.set(users));
      void firstValueFrom(this.api.tokens()).then((tokens) => this.tokens.set(tokens));
      void this.reloadMonitors();
      void this.reloadChannels();
      void this.loadDataRetention();
    }
  }

  tabLabel(tab: SettingsTab): string {
    return tab === 'data-retention' ? 'Data retention' : tab;
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

  channelTypeLabel(type: NotificationChannelType): string {
    return type === 'teams' ? 'Teams' : 'Generic JSON';
  }

  triggerLabel(trigger: NotificationTrigger): string {
    return this.channelTriggerOptions.find((option) => option.value === trigger)?.label ?? trigger;
  }

  channelProjectsLabel(channel: NotificationChannel): string {
    if (channel.project_filter.length === 0) return 'All projects';
    const names = new Map(this.projects().map((project) => [project.id, project.name]));
    return channel.project_filter.map((id) => names.get(id) ?? `#${id}`).join(', ');
  }

  channelEnvironmentsLabel(channel: NotificationChannel): string {
    return channel.environment_filter.length === 0
      ? 'All environments'
      : channel.environment_filter.join(', ');
  }

  async saveChannel(): Promise<void> {
    this.error.set(null);
    const body = {
      name: this.newChannelName,
      type: this.newChannelType,
      url: this.newChannelUrl,
      enabled: this.newChannelEnabled,
      triggers: this.channelTriggerOptions
        .map((option) => option.value)
        .filter((value) => this.channelTriggers[value]),
      // Derive from the selection map, not the loaded project list, so ids
      // scoped to a since-deleted project (still selected by editChannel, but
      // with no checkbox to render) survive a save instead of being silently
      // dropped — which would collapse the scope to "all projects".
      project_filter: Object.entries(this.channelProjectSelected)
        .filter(([, selected]) => selected)
        .map(([id]) => Number(id)),
      environment_filter: this.newChannelEnvironments
        .split(',')
        .map((name) => name.trim())
        .filter((name) => name.length > 0),
    };
    try {
      const editing = this.editingChannelId();
      if (editing === null) {
        await firstValueFrom(this.api.createNotificationChannel(body));
      } else {
        await firstValueFrom(this.api.updateNotificationChannel(editing, body));
      }
      this.resetChannelForm();
      await this.reloadChannels();
    } catch {
      this.error.set('Could not save notification channel — check the URL and pick a trigger.');
    }
  }

  editChannel(channel: NotificationChannel): void {
    this.editingChannelId.set(channel.id);
    this.confirmDeleteChannelId.set(null);
    this.error.set(null);
    this.newChannelName = channel.name;
    this.newChannelType = channel.type;
    this.newChannelUrl = channel.url;
    this.newChannelEnabled = channel.enabled;
    for (const option of this.channelTriggerOptions) {
      this.channelTriggers[option.value] = channel.triggers.includes(option.value);
    }
    this.channelProjectSelected = {};
    for (const id of channel.project_filter) this.channelProjectSelected[id] = true;
    this.newChannelEnvironments = channel.environment_filter.join(', ');
  }

  cancelChannelEdit(): void {
    this.resetChannelForm();
  }

  async toggleChannel(channel: NotificationChannel): Promise<void> {
    this.error.set(null);
    try {
      await firstValueFrom(
        this.api.updateNotificationChannel(channel.id, {
          name: channel.name,
          type: channel.type,
          url: channel.url,
          enabled: !channel.enabled,
          triggers: channel.triggers,
          project_filter: channel.project_filter,
          environment_filter: channel.environment_filter,
        }),
      );
      // If this channel is open in the edit form, keep the form's (invisible)
      // enabled flag in sync so a later save doesn't revert the toggle.
      if (this.editingChannelId() === channel.id) {
        this.newChannelEnabled = !channel.enabled;
      }
      await this.reloadChannels();
    } catch {
      this.error.set('Could not update notification channel.');
    }
  }

  requestDeleteChannel(id: number): void {
    this.confirmDeleteChannelId.set(id);
  }

  cancelDeleteChannel(): void {
    this.confirmDeleteChannelId.set(null);
  }

  async deleteChannel(channel: NotificationChannel): Promise<void> {
    await firstValueFrom(this.api.deleteNotificationChannel(channel.id));
    if (this.editingChannelId() === channel.id) this.resetChannelForm();
    this.confirmDeleteChannelId.set(null);
    await this.reloadChannels();
  }

  private resetChannelForm(): void {
    this.editingChannelId.set(null);
    this.newChannelName = '';
    this.newChannelType = 'teams';
    this.newChannelUrl = '';
    this.newChannelEnabled = true;
    this.channelTriggers = { new_issue: false, incident_started: false, incident_resolved: false };
    this.channelProjectSelected = {};
    this.newChannelEnvironments = '';
  }

  private async reloadChannels(): Promise<void> {
    this.channels.set(await firstValueFrom(this.api.notificationChannels()));
  }

  async saveDataRetention(): Promise<void> {
    this.retentionError.set(null);
    this.retentionSuccess.set(null);
    this.retentionSaving.set(true);
    const body: DataRetentionSetting = {
      enabled: this.retentionEnabled,
      retention_days: Number(this.retentionDays) as RetentionDays,
    };
    try {
      const saved = await firstValueFrom(this.api.updateDataRetention(body));
      this.retentionEnabled = saved.enabled;
      this.retentionDays = String(saved.retention_days) as RetentionDaysValue;
      this.retentionSuccess.set('Data retention settings saved.');
    } catch {
      this.retentionError.set('Could not save data retention settings.');
    } finally {
      this.retentionSaving.set(false);
    }
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

  private async loadDataRetention(): Promise<void> {
    this.retentionLoading.set(true);
    this.retentionError.set(null);
    try {
      const setting = await firstValueFrom(this.api.dataRetention());
      this.retentionEnabled = setting.enabled;
      this.retentionDays = String(setting.retention_days) as RetentionDaysValue;
    } catch {
      this.retentionError.set('Could not load data retention settings.');
    } finally {
      this.retentionLoading.set(false);
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
