import { ChangeDetectionStrategy, Component, effect, inject, signal } from '@angular/core';
import { httpResource } from '@angular/common/http';
import { DatePipe } from '@angular/common';
import { form, FormField, FormRoot, pattern, required, validateTree } from '@angular/forms/signals';
import { firstValueFrom } from 'rxjs';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmInput } from '@spartan-ng/helm/input';
import { HlmLabel } from '@spartan-ng/helm/label';
import { HlmSelectImports } from '@spartan-ng/helm/select';
import { HlmCheckbox } from '@spartan-ng/helm/checkbox';
import { HlmFieldImports } from '@spartan-ng/helm/field';

import { Api } from '../../../core/api';
import { API_BASE } from '../../../core/api-base';
import { Feedback } from '../../../core/feedback';
import {
  NotificationChannel,
  NotificationChannelInput,
  NotificationChannelType,
  NotificationDeliveryStatus,
  NotificationHistoryEntry,
  NotificationTestResult,
  NotificationTrigger,
} from '../../../core/models';
import { ProjectsStore } from '../../../core/projects';

/** Notification channels tab: webhook destinations, their scope, and delivery history. */
@Component({
  selector: 'app-notification-channel-settings',
  imports: [
    DatePipe,
    FormRoot,
    FormField,
    HlmButton,
    HlmInput,
    HlmLabel,
    HlmSelectImports,
    HlmCheckbox,
    ...HlmFieldImports,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './notification-channels.html',
})
export class NotificationChannelsSettings {
  private readonly api = inject(Api);
  private readonly feedback = inject(Feedback);
  readonly projectsStore = inject(ProjectsStore);

  private readonly channelsResource = httpResource<NotificationChannel[]>(
    () => `${API_BASE}/notifications/channels`,
    { defaultValue: [] },
  );
  readonly channels = this.channelsResource.value;

  readonly editingChannelId = signal<number | null>(null);
  readonly confirmDeleteChannelId = signal<number | null>(null);
  // Per-channel test outcome, cleared on the next test or edit; 'pending' while in flight.
  readonly channelTestResult = signal<Record<number, NotificationTestResult | 'pending'>>({});

  // Channel whose delivery history is expanded; historyResource below reacts to it.
  readonly expandedChannelId = signal<number | null>(null);
  private readonly historyResource = httpResource<NotificationHistoryEntry[]>(
    () => {
      const id = this.expandedChannelId();
      return id === null ? undefined : `${API_BASE}/notifications/channels/${id}/history`;
    },
    { defaultValue: [] },
  );
  readonly channelHistory = this.historyResource.value;
  readonly channelHistoryLoading = this.historyResource.isLoading;

  readonly channelTriggerOptions: { value: NotificationTrigger; label: string }[] = [
    { value: 'new_issue', label: 'New issue' },
    { value: 'incident_started', label: 'Incident started' },
    { value: 'incident_resolved', label: 'Incident resolved' },
  ];

  // `enabled` has no form control: it round-trips the per-row list toggle so a save doesn't revert it.
  private readonly model = signal({
    name: '',
    type: 'teams' as NotificationChannelType,
    url: '',
    enabled: true,
    triggers: { new_issue: false, incident_started: false, incident_resolved: false } as Record<
      NotificationTrigger,
      boolean
    >,
    environments: '',
    // Keyed by String(project id); a truthy entry means the channel is scoped to it.
    projectSelected: {} as Record<string, boolean>,
  });

  readonly channelForm = form(
    this.model,
    (path) => {
      required(path.name, { message: 'Name is required.' });
      required(path.url, { message: 'Webhook URL is required.' });
      pattern(path.url, /^https?:\/\/\S+$/i, { message: 'Enter a valid http(s) URL.' });
      // Requires at least one trigger; the tree validator attaches the error to the fieldset, not a checkbox.
      validateTree(path.triggers, ({ value }) => {
        const t = value();
        return t.new_issue || t.incident_started || t.incident_resolved
          ? null
          : { kind: 'atLeastOneTrigger', message: 'Select at least one trigger.' };
      });
    },
    {
      submission: {
        action: async () => {
          const editing = this.editingChannelId();
          try {
            const body = this.channelBody();
            if (editing === null) {
              await firstValueFrom(this.api.createNotificationChannel(body));
            } else {
              await firstValueFrom(this.api.updateNotificationChannel(editing, body));
            }
            this.resetChannelForm();
            this.channelsResource.reload();
            this.feedback.success(editing === null ? 'Channel created.' : 'Channel updated.');
          } catch {
            this.feedback.error(
              'Could not save notification channel — check the URL and pick a trigger.',
            );
          }
        },
      },
    },
  );

  constructor() {
    // Adds a backing field (default unchecked) for each newly-rendered project without disturbing existing selections, including since-deleted projects that have no checkbox.
    effect(() => {
      const projects = this.projectsStore.projects();
      const current = this.model().projectSelected;
      const missing = projects.filter((project) => !(String(project.id) in current));
      if (missing.length === 0) return;
      this.model.update((m) => {
        const projectSelected = { ...m.projectSelected };
        for (const project of missing) projectSelected[String(project.id)] = false;
        return { ...m, projectSelected };
      });
    });
  }

  readonly typeLabel = (value: string): string =>
    this.channelTypeLabel(value as NotificationChannelType);

  private blankProjectSelection(): Record<string, boolean> {
    const selection: Record<string, boolean> = {};
    for (const project of this.projectsStore.projects()) {
      selection[String(project.id)] = false;
    }
    return selection;
  }

  /** Builds the DTO from the form model: booleans → string[]/number[]. */
  private channelBody(): NotificationChannelInput {
    const m = this.model();
    return {
      name: m.name,
      type: m.type,
      url: m.url,
      enabled: m.enabled,
      triggers: this.channelTriggerOptions
        .map((option) => option.value)
        .filter((value) => m.triggers[value]),
      // Reads the selection map, not the loaded project list, so a since-deleted project's scope survives instead of collapsing to "all".
      project_filter: Object.entries(m.projectSelected)
        .filter(([, selected]) => selected)
        .map(([id]) => Number(id)),
      environment_filter: m.environments
        .split(',')
        .map((name) => name.trim())
        .filter((name) => name.length > 0),
    };
  }

  channelTypeLabel(type: NotificationChannelType): string {
    return type === 'teams' ? 'Teams' : 'Generic JSON';
  }

  triggerLabel(trigger: NotificationTrigger): string {
    return this.channelTriggerOptions.find((option) => option.value === trigger)?.label ?? trigger;
  }

  channelProjectsLabel(channel: NotificationChannel): string {
    if (channel.project_filter.length === 0) return 'All projects';
    return channel.project_filter.map((id) => this.projectsStore.name(id)).join(', ');
  }

  channelEnvironmentsLabel(channel: NotificationChannel): string {
    return channel.environment_filter.length === 0
      ? 'All environments'
      : channel.environment_filter.join(', ');
  }

  editChannel(channel: NotificationChannel): void {
    this.editingChannelId.set(channel.id);
    this.confirmDeleteChannelId.set(null);
    this.channelTestResult.update((map) => {
      const next = { ...map };
      delete next[channel.id];
      return next;
    });
    const triggers = {
      new_issue: false,
      incident_started: false,
      incident_resolved: false,
    } as Record<NotificationTrigger, boolean>;
    for (const option of this.channelTriggerOptions) {
      triggers[option.value] = channel.triggers.includes(option.value);
    }
    // Unchecked for every current project, then checked for the channel's scope; since-deleted-project ids are kept since channelBody reads the whole map.
    const projectSelected = this.blankProjectSelection();
    for (const id of channel.project_filter) projectSelected[String(id)] = true;
    this.channelForm().reset({
      name: channel.name,
      type: channel.type,
      url: channel.url,
      enabled: channel.enabled,
      triggers,
      environments: channel.environment_filter.join(', '),
      projectSelected,
    });
  }

  cancelChannelEdit(): void {
    this.resetChannelForm();
  }

  async toggleChannel(channel: NotificationChannel): Promise<void> {
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
      // Keeps the form's enabled flag in sync if this channel is open for edit, so a later save doesn't revert the toggle.
      if (this.editingChannelId() === channel.id) {
        this.model.update((m) => ({ ...m, enabled: !channel.enabled }));
      }
      this.channelsResource.reload();
    } catch {
      this.feedback.error('Could not update notification channel.');
    }
  }

  requestDeleteChannel(id: number): void {
    this.confirmDeleteChannelId.set(id);
  }

  cancelDeleteChannel(): void {
    this.confirmDeleteChannelId.set(null);
  }

  async deleteChannel(channel: NotificationChannel): Promise<void> {
    try {
      await firstValueFrom(this.api.deleteNotificationChannel(channel.id));
      if (this.editingChannelId() === channel.id) this.resetChannelForm();
      this.confirmDeleteChannelId.set(null);
      this.channelsResource.reload();
      this.feedback.success('Channel deleted.');
    } catch {
      this.feedback.error('Could not delete notification channel.');
    }
  }

  /**
   * Sends a real test notification through the full pipeline and shows the
   * outcome inline; on success also reloads the list and, if open, the history.
   */
  async testChannel(channel: NotificationChannel): Promise<void> {
    this.channelTestResult.update((map) => ({ ...map, [channel.id]: 'pending' }));
    try {
      const result = await firstValueFrom(this.api.testNotificationChannel(channel.id));
      this.channelTestResult.update((map) => ({ ...map, [channel.id]: result }));
      this.channelsResource.reload();
      if (this.expandedChannelId() === channel.id) {
        this.historyResource.reload();
      }
    } catch {
      this.channelTestResult.update((map) => {
        const next = { ...map };
        delete next[channel.id];
        return next;
      });
      this.feedback.error('Could not test channel — it may be disabled or unreachable.');
    }
  }

  testResultFor(channelId: number): NotificationTestResult | 'pending' | undefined {
    return this.channelTestResult()[channelId];
  }

  toggleChannelHistory(channel: NotificationChannel): void {
    this.expandedChannelId.set(this.expandedChannelId() === channel.id ? null : channel.id);
  }

  historyTriggerLabel(trigger: NotificationHistoryEntry['trigger_type']): string {
    return trigger === 'test' ? 'Test' : this.triggerLabel(trigger);
  }

  /** CSS color token for a delivery status. */
  deliveryStatusColor(status: NotificationDeliveryStatus | null): string {
    switch (status) {
      case 'sent':
        return 'var(--level-success)';
      case 'failed':
        return 'var(--level-error)';
      case 'suppressed':
        return 'var(--level-warn)';
      default:
        return 'var(--muted-foreground)';
    }
  }

  private resetChannelForm(): void {
    this.editingChannelId.set(null);
    this.channelForm().reset({
      name: '',
      type: 'teams',
      url: '',
      enabled: true,
      triggers: { new_issue: false, incident_started: false, incident_resolved: false },
      environments: '',
      projectSelected: this.blankProjectSelection(),
    });
  }
}
