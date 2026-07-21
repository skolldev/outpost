import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { httpResource } from '@angular/common/http';
import { email, form, FormField, FormRoot, minLength, required } from '@angular/forms/signals';
import { firstValueFrom } from 'rxjs';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmInput } from '@spartan-ng/helm/input';
import { HlmFieldImports } from '@spartan-ng/helm/field';
import { HlmSelectImports } from '@spartan-ng/helm/select';

import { Api } from '../../../core/api';
import { API_BASE } from '../../../core/api-base';
import { Feedback } from '../../../core/feedback';
import { AppUser } from '../../../core/models';

@Component({
  selector: 'app-outpost-user-settings',
  imports: [FormRoot, FormField, HlmButton, HlmInput, HlmFieldImports, HlmSelectImports],
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

  private readonly model = signal({ email: '', password: '', role: '' });

  readonly userForm = form(
    this.model,
    (path) => {
      required(path.email, { message: 'Email is required.' });
      email(path.email, { message: 'Enter a valid email address.' });
      required(path.password, { message: 'Password is required.' });
      minLength(path.password, 8, { message: 'Password must be at least 8 characters.' });
      required(path.role, { message: 'Select a role.' });
    },
    {
      submission: {
        action: async () => {
          const { email, password, role } = this.model();
          try {
            await firstValueFrom(this.api.createUser(email, password, role));
            this.userForm().reset({ email: '', password: '', role: '' });
            this.usersResource.reload();
            this.feedback.success('User created.');
          } catch {
            this.feedback.error('Could not create user.');
          }
        },
      },
    },
  );

  // Single source of truth for the role picker; the trigger label is derived
  // from it. member/admin are the only roles an Outpost User can hold.
  readonly roles = [
    { value: 'member', label: 'member' },
    { value: 'admin', label: 'admin' },
  ];

  /** Maps a role value to its display label for the select trigger. */
  readonly roleLabel = (value: string): string =>
    this.roles.find((role) => role.value === value)?.label ?? value;
}
