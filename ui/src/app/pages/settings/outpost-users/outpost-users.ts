import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { httpResource } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmInput } from '@spartan-ng/helm/input';
import { HlmLabel } from '@spartan-ng/helm/label';
import { HlmNativeSelect, HlmNativeSelectOption } from '@spartan-ng/helm/native-select';

import { Api } from '../../../core/api';
import { API_BASE } from '../../../core/api-base';
import { Feedback } from '../../../core/feedback';
import { AppUser } from '../../../core/models';

/** Users tab: the Outpost Users who can sign in, and their roles. */
@Component({
  selector: 'app-outpost-user-settings',
  imports: [FormsModule, HlmButton, HlmInput, HlmLabel, HlmNativeSelect, HlmNativeSelectOption],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './outpost-users.html',
})
export class OutpostUsersSettings {
  private readonly api = inject(Api);
  private readonly feedback = inject(Feedback);

  private readonly usersResource = httpResource<AppUser[]>(() => `${API_BASE}/users`, {
    defaultValue: [],
  });
  readonly users = this.usersResource.value;

  newEmail = '';
  newPassword = '';
  newRole = 'member';

  async createUser(): Promise<void> {
    try {
      await firstValueFrom(this.api.createUser(this.newEmail, this.newPassword, this.newRole));
      this.newEmail = '';
      this.newPassword = '';
      this.usersResource.reload();
      this.feedback.success('User created.');
    } catch {
      this.feedback.error('Could not create user.');
    }
  }
}
