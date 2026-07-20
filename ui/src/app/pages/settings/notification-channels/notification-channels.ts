import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { httpResource } from '@angular/common/http';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmInput } from '@spartan-ng/helm/input';
import { HlmLabel } from '@spartan-ng/helm/label';
import { HlmNativeSelect, HlmNativeSelectOption } from '@spartan-ng/helm/native-select';
import { HlmCheckbox } from '@spartan-ng/helm/checkbox';
import { HlmFieldImports } from '@spartan-ng/helm/field';

import { Api } from '../../../core/api';
import { API_BASE } from '../../../core/api-base';
import { Feedback } from '../../../core/feedback';
import {
  NotificationChannel,
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
    FormsModule,
    HlmButton,
    HlmInput,
    HlmLabel,
    HlmNativeSelect,
    HlmNativeSelectOption,
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
  // Per-channel test-send outcome, shown inline until the next test or edit.
  // 'pending' while the request is in flight.
  readonly channelTestResult = signal<Record<number, NotificationTestResult | 'pending'>>({});

  // The channel whose delivery history is expanded; the rows load reactively.
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

  async saveChannel(): Promise<void> {
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
    const editing = this.editingChannelId();
    try {
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
  }

  editChannel(channel: NotificationChannel): void {
    this.editingChannelId.set(channel.id);
    this.confirmDeleteChannelId.set(null);
    this.channelTestResult.update((map) => {
      const next = { ...map };
      delete next[channel.id];
      return next;
    });
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
   * Fire a real test Notification through the full pipeline and show the outcome
   * inline. On success we also reload the list (its last-outcome column moves)
   * and, if the channel's history is open, refresh it so the new row appears.
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

  /** A CSS color token for a delivery status, matching the status stripes elsewhere. */
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
    this.newChannelName = '';
    this.newChannelType = 'teams';
    this.newChannelUrl = '';
    this.newChannelEnabled = true;
    this.channelTriggers = { new_issue: false, incident_started: false, incident_resolved: false };
    this.channelProjectSelected = {};
    this.newChannelEnvironments = '';
  }
}
