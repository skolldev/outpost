import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { httpResource } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmInput } from '@spartan-ng/helm/input';
import { HlmLabel } from '@spartan-ng/helm/label';
import { HlmNativeSelect, HlmNativeSelectOption } from '@spartan-ng/helm/native-select';

import { Api } from '../../core/api';
import { API_BASE } from '../../core/api-base';
import { UptimeMonitor, UptimeTestResult } from '../../core/models';
import { ProjectsStore } from '../../core/projects';

/** Uptime monitors tab: configure the URLs Outpost polls for availability. */
@Component({
  selector: 'app-uptime-monitor-settings',
  imports: [FormsModule, HlmButton, HlmInput, HlmLabel, HlmNativeSelect, HlmNativeSelectOption],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './uptime-monitors.html',
})
export class UptimeMonitorsSettings {
  private readonly api = inject(Api);
  readonly projectsStore = inject(ProjectsStore);

  private readonly monitorsResource = httpResource<UptimeMonitor[]>(
    () => `${API_BASE}/uptime/monitors`,
    { defaultValue: [] },
  );
  readonly monitors = this.monitorsResource.value;

  readonly error = signal<string | null>(null);
  readonly testResult = signal<UptimeTestResult | 'pending' | null>(null);
  readonly editingMonitorId = signal<number | null>(null);

  // Signal (not a plain field) so the environments resource re-fetches when the
  // selected Project changes.
  readonly newMonitorProjectId = signal('');
  private readonly envsResource = httpResource<string[]>(
    () => {
      const id = Number(this.newMonitorProjectId());
      return id ? `${API_BASE}/projects/${id}/environments` : undefined;
    },
    { defaultValue: [] },
  );
  readonly monitorEnvs = this.envsResource.value;

  newMonitorEnv = '';
  newMonitorUrl = '';
  newMonitorInterval = '60';
  newMonitorTimeout = 10;

  onMonitorProjectChange(projectId: string): void {
    this.newMonitorProjectId.set(projectId);
    this.newMonitorEnv = '';
  }

  async saveMonitor(): Promise<void> {
    this.error.set(null);
    const body = {
      project_id: Number(this.newMonitorProjectId()),
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
      this.monitorsResource.reload();
    } catch {
      this.error.set('Could not save monitor — check the URL.');
    }
  }

  editMonitor(monitor: UptimeMonitor): void {
    this.editingMonitorId.set(monitor.id);
    // Setting the project id triggers the environments resource; the env value
    // applies once the list arrives.
    this.newMonitorProjectId.set(String(monitor.project_id));
    this.newMonitorEnv = monitor.environment;
    this.newMonitorUrl = monitor.url;
    this.newMonitorInterval = String(monitor.interval_seconds);
    this.newMonitorTimeout = monitor.timeout_seconds;
    this.testResult.set(null);
  }

  cancelMonitorEdit(): void {
    this.resetMonitorForm();
  }

  async deleteMonitor(monitor: UptimeMonitor): Promise<void> {
    await firstValueFrom(this.api.deleteUptimeMonitor(monitor.id));
    if (this.editingMonitorId() === monitor.id) {
      this.resetMonitorForm();
    }
    this.monitorsResource.reload();
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
    this.newMonitorProjectId.set('');
    this.newMonitorEnv = '';
    this.newMonitorUrl = '';
    this.newMonitorInterval = '60';
    this.newMonitorTimeout = 10;
    this.testResult.set(null);
  }
}
