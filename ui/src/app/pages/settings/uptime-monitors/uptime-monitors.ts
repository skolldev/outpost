import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { httpResource } from '@angular/common/http';
import { form, FormField, FormRoot, max, min, pattern, required } from '@angular/forms/signals';
import { firstValueFrom } from 'rxjs';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmInput } from '@spartan-ng/helm/input';
import { HlmFieldImports } from '@spartan-ng/helm/field';
import { HlmSelectImports } from '@spartan-ng/helm/select';

import { Api } from '../../../core/api';
import { API_BASE } from '../../../core/api-base';
import { Feedback } from '../../../core/feedback';
import { UptimeMonitor, UptimeTestResult } from '../../../core/models';
import { ProjectsStore } from '../../../core/projects';

/** Uptime monitors tab: configure the URLs Outpost polls for availability. */
@Component({
  selector: 'app-uptime-monitor-settings',
  imports: [FormRoot, FormField, HlmButton, HlmInput, HlmFieldImports, HlmSelectImports],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './uptime-monitors.html',
})
export class UptimeMonitorsSettings {
  private readonly api = inject(Api);
  private readonly feedback = inject(Feedback);
  readonly projectsStore = inject(ProjectsStore);

  private readonly monitorsResource = httpResource<UptimeMonitor[]>(
    () => `${API_BASE}/uptime/monitors`,
    { defaultValue: [] },
  );
  readonly monitors = this.monitorsResource.value;

  // The detailed probe result stays inline (per-row data, not a transient
  // confirmation); action outcomes go through the Feedback seam.
  readonly testResult = signal<UptimeTestResult | 'pending' | null>(null);
  readonly editingMonitorId = signal<number | null>(null);

  // Typed form model. The select/text controls yield strings (project id and
  // interval are coerced to numbers at the DTO boundary); the number input binds
  // timeout as a number so min/max validators apply.
  private readonly model = signal({
    projectId: '',
    environment: '',
    url: '',
    interval: '60',
    timeout: 10,
  });

  readonly monitorForm = form(
    this.model,
    (path) => {
      required(path.projectId, { message: 'Select a project.' });
      required(path.environment, { message: 'Environment is required.' });
      required(path.url, { message: 'URL is required.' });
      pattern(path.url, /^https?:\/\/\S+$/i, { message: 'Enter a valid http(s) URL.' });
      // interval defaults to '60' and the select has no empty option, so it
      // never needs a required rule.
      required(path.timeout, { message: 'Timeout is required.' });
      min(path.timeout, 1, { message: 'Timeout must be between 1 and 30 seconds.' });
      max(path.timeout, 30, { message: 'Timeout must be between 1 and 30 seconds.' });
    },
    {
      submission: {
        action: async () => {
          const m = this.model();
          const body = {
            project_id: Number(m.projectId),
            environment: m.environment,
            url: m.url,
            interval_seconds: Number(m.interval),
            timeout_seconds: m.timeout,
          };
          const editing = this.editingMonitorId();
          try {
            if (editing === null) {
              await firstValueFrom(this.api.createUptimeMonitor(body));
            } else {
              await firstValueFrom(this.api.updateUptimeMonitor(editing, body));
            }
            this.resetMonitorForm();
            this.monitorsResource.reload();
            this.feedback.success(editing === null ? 'Monitor created.' : 'Monitor updated.');
          } catch {
            this.feedback.error('Could not save monitor — check the URL.');
          }
        },
      },
    },
  );

  // Environments for the selected project. Re-fetches when projectId changes;
  // empty while loading so switching projects never leaves the prior project's
  // options selectable (httpResource retains its last value across a re-fetch).
  private readonly envsResource = httpResource<string[]>(
    () => {
      const id = Number(this.model().projectId);
      return id ? `${API_BASE}/projects/${id}/environments` : undefined;
    },
    { defaultValue: [] },
  );
  readonly monitorEnvs = computed(() =>
    this.envsResource.isLoading() ? [] : this.envsResource.value(),
  );

  // Project picker options; string values match the model field so the select
  // binds and the trigger label resolves without per-render conversions.
  readonly projectOptions = computed(() =>
    this.projectsStore.projects().map((project) => ({
      value: String(project.id),
      name: project.name,
    })),
  );
  readonly projectLabel = (value: string): string =>
    this.projectOptions().find((project) => project.value === value)?.name ?? value;

  // Interval choices; values are the string form of the seconds stored on the
  // DTO, labels precomputed from intervalLabel so the template loop and the
  // select trigger both read them off the option (no per-render conversion).
  readonly intervals = [30, 60, 300, 900, 3600].map((seconds) => ({
    value: String(seconds),
    label: this.intervalLabel(seconds),
  }));
  readonly intervalLabelFor = (value: string): string =>
    this.intervals.find((option) => option.value === value)?.label ?? value;

  // Environment select shows the raw environment string as its own label.
  readonly envLabel = (value: string): string => value;

  editMonitor(monitor: UptimeMonitor): void {
    this.editingMonitorId.set(monitor.id);
    // Setting the project id triggers the environments resource; the env value
    // applies once the list arrives.
    this.monitorForm().reset({
      projectId: String(monitor.project_id),
      environment: monitor.environment,
      url: monitor.url,
      interval: String(monitor.interval_seconds),
      timeout: monitor.timeout_seconds,
    });
    this.testResult.set(null);
  }

  cancelMonitorEdit(): void {
    this.resetMonitorForm();
  }

  async deleteMonitor(monitor: UptimeMonitor): Promise<void> {
    try {
      await firstValueFrom(this.api.deleteUptimeMonitor(monitor.id));
      if (this.editingMonitorId() === monitor.id) {
        this.resetMonitorForm();
      }
      this.monitorsResource.reload();
      this.feedback.success('Monitor deleted.');
    } catch {
      this.feedback.error('Could not delete monitor.');
    }
  }

  async testMonitor(): Promise<void> {
    this.testResult.set('pending');
    const { url, timeout } = this.model();
    try {
      this.testResult.set(await firstValueFrom(this.api.testUptimeMonitor(url, Number(timeout))));
    } catch {
      this.testResult.set(null);
      this.feedback.error('Test request failed — check the URL.');
    }
  }

  intervalLabel(seconds: number): string {
    return seconds < 60 ? `${seconds}s` : `${seconds / 60}m`;
  }

  private resetMonitorForm(): void {
    this.editingMonitorId.set(null);
    this.monitorForm().reset({
      projectId: '',
      environment: '',
      url: '',
      interval: '60',
      timeout: 10,
    });
    this.testResult.set(null);
  }
}
