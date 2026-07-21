import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { email, form, FormField, FormRoot, required } from '@angular/forms/signals';
import { ActivatedRoute, Router } from '@angular/router';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmInput } from '@spartan-ng/helm/input';
import { HlmLabel } from '@spartan-ng/helm/label';
import { HlmCard } from '@spartan-ng/helm/card';
import { HlmFieldImports } from '@spartan-ng/helm/field';
import { NgIconComponent, provideIcons } from '@ng-icons/core';
import { lucideTowerControl } from '@ng-icons/lucide';

import { Session } from '../../core/session';
import { Feedback } from '../../core/feedback';

@Component({
  selector: 'app-login',
  imports: [
    FormRoot,
    FormField,
    HlmButton,
    HlmInput,
    HlmLabel,
    HlmCard,
    ...HlmFieldImports,
    NgIconComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [provideIcons({ lucideTowerControl })],
  templateUrl: './login.html',
})
export class LoginPage {
  private readonly session = inject(Session);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly feedback = inject(Feedback);

  private readonly model = signal({ email: '', password: '' });

  readonly loginForm = form(
    this.model,
    (path) => {
      required(path.email, { message: 'Email is required.' });
      email(path.email, { message: 'Enter a valid email address.' });
      required(path.password, { message: 'Password is required.' });
    },
    {
      // `FormRoot` runs this on submit, and only when the form is valid — so
      // client-side errors surface inline first. A rejected login is a *server*
      // outcome, reported through the `Feedback` toast seam (ADR 0007), never
      // mapped onto a field.
      submission: {
        action: async () => {
          try {
            await this.session.login(this.model().email, this.model().password);
            const returnUrl = this.route.snapshot.queryParamMap.get('returnUrl') ?? '/issues';
            await this.router.navigateByUrl(returnUrl);
          } catch {
            this.feedback.error('Invalid email or password.');
          }
        },
      },
    },
  );
}
