import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { httpResource } from '@angular/common/http';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmInput } from '@spartan-ng/helm/input';
import { HlmLabel } from '@spartan-ng/helm/label';
import { HlmAlert, HlmAlertTitle, HlmAlertDescription } from '@spartan-ng/helm/alert';
import { Api } from '../../../core/api';
import { API_BASE } from '../../../core/api-base';
import { Feedback } from '../../../core/feedback';
import { ApiToken } from '../../../core/models';

/** API tokens tab: named secrets for sentry-cli source-map uploads from CI. */
@Component({
  selector: 'app-api-token-settings',
  imports: [
    DatePipe,
    FormsModule,
    HlmButton,
    HlmInput,
    HlmLabel,
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

  // The created-token reveal is the success confirmation for a create (it holds
  // the one-time secret), so create emits no success toast — only an error one.
  readonly createdToken = signal<ApiToken | null>(null);
  readonly copied = signal<string | null>(null);

  newTokenName = '';

  async createToken(): Promise<void> {
    try {
      const created = await firstValueFrom(this.api.createToken(this.newTokenName));
      this.createdToken.set(created);
      this.newTokenName = '';
      this.tokensResource.reload();
    } catch {
      this.feedback.error('Could not create token.');
    }
  }

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
