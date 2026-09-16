import { ChangeDetectionStrategy, Component, effect, inject, signal } from '@angular/core';
import { httpResource } from '@angular/common/http';
import { form, FormField, FormRoot, required } from '@angular/forms/signals';
import { firstValueFrom } from 'rxjs';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmSelectImports } from '@spartan-ng/helm/select';
import { HlmCardImports } from '@spartan-ng/helm/card';
import { HlmAlert, HlmAlertTitle, HlmAlertDescription } from '@spartan-ng/helm/alert';
import { HlmCheckbox } from '@spartan-ng/helm/checkbox';
import { HlmFieldImports } from '@spartan-ng/helm/field';
import { HlmSpinner } from '@spartan-ng/helm/spinner';

import { Api } from '../../../core/api';
import { API_BASE } from '../../../core/api-base';
import { Feedback } from '../../../core/feedback';
import { DataRetentionSetting, RetentionDays } from '../../../core/models';

type RetentionDaysValue = '30' | '60' | '90' | '180';

/** Data retention tab: how long telemetry is kept before the daily cleanup removes it. */
@Component({
  selector: 'app-data-retention-settings',
  imports: [
    FormRoot,
    FormField,
    HlmButton,
    HlmSelectImports,
    ...HlmCardImports,
    HlmAlert,
    HlmAlertTitle,
    HlmAlertDescription,
    HlmCheckbox,
    ...HlmFieldImports,
    HlmSpinner,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './data-retention.html',
})
export class DataRetentionSettings {
  private readonly api = inject(Api);
  private readonly feedback = inject(Feedback);

  // String values, coerced to a number at the DTO boundary; fixed options need only `required`.
  readonly retentionOptions: { value: RetentionDaysValue; label: string }[] = [
    { value: '30', label: '30 days' },
    { value: '60', label: '60 days' },
    { value: '90', label: '90 days' },
    { value: '180', label: '180 days' },
  ];
  readonly retentionDaysLabel = (value: string): string =>
    this.retentionOptions.find((option) => option.value === value)?.label ?? value;

  // Fetched here; a seeding effect below copies the value into the form once it arrives.
  private readonly retentionResource = httpResource<DataRetentionSetting>(
    () => `${API_BASE}/settings/data-retention`,
  );
  readonly retentionLoading = this.retentionResource.isLoading;

  // Load-failure state only; save outcomes go through the Feedback seam instead.
  readonly retentionError = signal<string | null>(null);

  // retentionDays starts empty and required, so submit stays disabled until the saved setting hydrates it (or the user picks one).
  private readonly model = signal<{ enabled: boolean; retentionDays: RetentionDaysValue | '' }>({
    enabled: false,
    retentionDays: '',
  });

  readonly retentionForm = form(
    this.model,
    (path) => {
      required(path.retentionDays, { message: 'Select a retention period.' });
    },
    {
      submission: {
        action: async () => {
          const m = this.model();
          const body: DataRetentionSetting = {
            enabled: m.enabled,
            retention_days: Number(m.retentionDays) as RetentionDays,
          };
          try {
            const saved = await firstValueFrom(this.api.updateDataRetention(body));
            this.seedForm(saved);
            this.feedback.success(
              'Data retention settings saved. Changes take effect at the next 02:00 UTC run.',
            );
          } catch {
            this.feedback.error('Could not save data retention settings.');
          }
        },
      },
    },
  );

  constructor() {
    effect(() => {
      // value() throws while loading or errored; only read it once hasValue() is true.
      if (this.retentionResource.hasValue()) {
        this.seedForm(this.retentionResource.value());
      }
    });
    effect(() => {
      if (this.retentionResource.error()) {
        this.retentionError.set('Could not load data retention settings.');
      }
    });
  }

  /** Copies a saved setting into the form, restoring the DTO number as a select-value string. */
  private seedForm(setting: DataRetentionSetting): void {
    this.retentionForm().reset({
      enabled: setting.enabled,
      retentionDays: String(setting.retention_days) as RetentionDaysValue,
    });
  }
}
