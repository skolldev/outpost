import { ChangeDetectionStrategy, Component, effect, inject, signal } from '@angular/core';
import { httpResource } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmNativeSelect, HlmNativeSelectOption } from '@spartan-ng/helm/native-select';
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
    FormsModule,
    HlmButton,
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
  templateUrl: './data-retention.html',
})
export class DataRetentionSettings {
  private readonly api = inject(Api);
  private readonly feedback = inject(Feedback);

  readonly retentionDurations: RetentionDaysValue[] = ['30', '60', '90', '180'];

  // Read-into-form: the resource fetches the current setting, and a seeding
  // effect copies it into the editable form fields once it arrives.
  private readonly retentionResource = httpResource<DataRetentionSetting>(
    () => `${API_BASE}/settings/data-retention`,
  );
  readonly retentionLoading = this.retentionResource.isLoading;

  readonly retentionSaving = signal(false);
  // Persistent page-state error for a failed *load*; the panel is unusable
  // until it clears. Save outcomes go through the Feedback seam instead.
  readonly retentionError = signal<string | null>(null);

  retentionEnabled = false;
  retentionDays: RetentionDaysValue = '90';

  constructor() {
    effect(() => {
      // value() throws while the resource is loading or errored; only read it
      // once a value is actually present.
      if (this.retentionResource.hasValue()) {
        const setting = this.retentionResource.value();
        this.retentionEnabled = setting.enabled;
        this.retentionDays = String(setting.retention_days) as RetentionDaysValue;
      }
    });
    effect(() => {
      if (this.retentionResource.error()) {
        this.retentionError.set('Could not load data retention settings.');
      }
    });
  }

  async saveDataRetention(): Promise<void> {
    this.retentionSaving.set(true);
    const body: DataRetentionSetting = {
      enabled: this.retentionEnabled,
      retention_days: Number(this.retentionDays) as RetentionDays,
    };
    try {
      const saved = await firstValueFrom(this.api.updateDataRetention(body));
      this.retentionEnabled = saved.enabled;
      this.retentionDays = String(saved.retention_days) as RetentionDaysValue;
      this.feedback.success(
        'Data retention settings saved. Changes take effect at the next 02:00 UTC run.',
      );
    } catch {
      this.feedback.error('Could not save data retention settings.');
    } finally {
      this.retentionSaving.set(false);
    }
  }
}
