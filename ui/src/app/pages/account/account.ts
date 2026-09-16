import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { form, FormField, FormRoot, minLength, required, validate } from '@angular/forms/signals';
import { firstValueFrom } from 'rxjs';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmInput } from '@spartan-ng/helm/input';
import { HlmFieldImports } from '@spartan-ng/helm/field';

import { Api } from '../../core/api';
import { Feedback } from '../../core/feedback';
import { MIN_PASSWORD_LENGTH, MIN_PASSWORD_LENGTH_MESSAGE } from '../../core/password-policy';
import { Session } from '../../core/session';

/**
 * The signed-in user's own account — the one authenticated surface that isn't
 * Admin-only. Changing a password leaves the Session untouched (ADR 0012): no
 * re-issue needed, and the user stays signed in.
 */
@Component({
  selector: 'app-account',
  imports: [FormRoot, FormField, HlmButton, HlmInput, HlmFieldImports],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './account.html',
})
export class AccountPage {
  private readonly api = inject(Api);
  private readonly feedback = inject(Feedback);
  readonly session = inject(Session);

  private readonly empty = { currentPassword: '', newPassword: '', confirmPassword: '' };

  private readonly model = signal({ ...this.empty });

  readonly passwordForm = form(
    this.model,
    (path) => {
      required(path.currentPassword, { message: 'Current password is required.' });
      required(path.newPassword, { message: 'New password is required.' });
      minLength(path.newPassword, MIN_PASSWORD_LENGTH, { message: MIN_PASSWORD_LENGTH_MESSAGE });
      required(path.confirmPassword, { message: 'Confirm the new password.' });
      // Cross-field check reported on confirmPassword; the server has no
      // equivalent — this exists only to catch a typo before submit.
      validate(path.confirmPassword, ({ value, valueOf }) =>
        value() === valueOf(path.newPassword)
          ? null
          : { kind: 'mismatch', message: 'Passwords do not match.' },
      );
    },
    {
      submission: {
        action: async () => {
          const { currentPassword, newPassword } = this.model();
          try {
            await firstValueFrom(this.api.changePassword(currentPassword, newPassword));
          } catch (error) {
            // 401 is the only outcome validation can't catch — other errors
            // must not be reported as a bad password.
            const status = (error as HttpErrorResponse | undefined)?.status;
            this.feedback.error(
              status === 401 ? 'Current password is incorrect.' : 'Could not change password.',
            );
            return;
          }
          this.passwordForm().reset({ ...this.empty });
          this.feedback.success('Password changed.');
        },
      },
    },
  );
}
