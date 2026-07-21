import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { httpResource } from '@angular/common/http';
import { DatePipe } from '@angular/common';
import { form, FormField, FormRoot, required } from '@angular/forms/signals';
import { firstValueFrom } from 'rxjs';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmInput } from '@spartan-ng/helm/input';
import { HlmFieldImports } from '@spartan-ng/helm/field';
import { HlmAlert, HlmAlertTitle, HlmAlertDescription } from '@spartan-ng/helm/alert';
import { Api } from '../../../core/api';
import { API_BASE } from '../../../core/api-base';
import { Feedback } from '../../../core/feedback';
import { ApiToken } from '../../../core/models';

@Component({
  selector: 'app-api-token-settings',
  imports: [
    DatePipe,
    FormRoot,
    FormField,
    HlmButton,
    HlmInput,
    ...HlmFieldImports,
    HlmAlert,
    HlmAlertTitle,
    HlmAlertDescription,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './api-tokens.html',
})
export class ApiTokensSettings {
  private readonly api = inject(Api);
  private readonly feedback = inject(Feedback);

  private readonly tokensResource = httpResource<ApiToken[]>(() => `${API_BASE}/tokens`, {
    defaultValue: [],
  });
  readonly tokens = this.tokensResource.value;

  readonly createdToken = signal<ApiToken | null>(null);
  readonly copied = signal<string | null>(null);

  private readonly model = signal({ name: '' });

  readonly tokenForm = form(
    this.model,
    (path) => {
      required(path.name, { message: 'Token name is required.' });
    },
    {
      submission: {
        action: async () => {
          try {
            const created = await firstValueFrom(this.api.createToken(this.model().name));
            this.createdToken.set(created);
            this.tokenForm().reset({ name: '' });
            this.tokensResource.reload();
          } catch {
            this.feedback.error('Could not create token.');
          }
        },
      },
    },
  );

  async deleteToken(token: ApiToken): Promise<void> {
    try {
      await firstValueFrom(this.api.deleteToken(token.id));
      if (this.createdToken()?.id === token.id) {
        this.createdToken.set(null);
      }
      this.tokensResource.reload();
      this.feedback.success('Token deleted.');
    } catch {
      this.feedback.error('Could not delete token.');
    }
  }

  cliSnippet(token: string): string {
    return `# CI: upload source maps after ng build
export SENTRY_URL=${location.origin}
export SENTRY_AUTH_TOKEN=${token}
export SENTRY_ORG=outpost
export SENTRY_PROJECT=<project-slug>
sentry-cli sourcemaps inject ./dist/<app>/browser
sentry-cli sourcemaps upload --release "<app>@$VERSION" ./dist/<app>/browser`;
  }

  copy(text: string): void {
    void navigator.clipboard.writeText(text).then(() => {
      this.copied.set(text);
      setTimeout(() => this.copied.set(null), 1500);
    });
  }
}
