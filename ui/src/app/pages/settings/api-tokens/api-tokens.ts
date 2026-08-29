import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { httpResource } from '@angular/common/http';
import { DatePipe } from '@angular/common';
import { form, FormField, FormRoot, required, validateTree } from '@angular/forms/signals';
import { firstValueFrom } from 'rxjs';
import { HlmButton } from '@spartan-ng/helm/button';
import { HlmInput } from '@spartan-ng/helm/input';
import { HlmLabel } from '@spartan-ng/helm/label';
import { HlmCheckbox } from '@spartan-ng/helm/checkbox';
import { HlmFieldImports } from '@spartan-ng/helm/field';
import { HlmSelectImports } from '@spartan-ng/helm/select';
import { HlmAlert, HlmAlertTitle, HlmAlertDescription } from '@spartan-ng/helm/alert';
import { Api } from '../../../core/api';
import { API_BASE } from '../../../core/api-base';
import { Feedback } from '../../../core/feedback';
import { Session } from '../../../core/session';
import { ApiToken, TokenScope } from '../../../core/models';

/** Ownership of a new token — the two kinds ADR-0017 distinguishes. */
type Ownership = 'personal' | 'installation';

@Component({
  selector: 'app-api-token-settings',
  imports: [
    DatePipe,
    FormRoot,
    FormField,
    HlmButton,
    HlmInput,
    HlmLabel,
    HlmCheckbox,
    ...HlmFieldImports,
    ...HlmSelectImports,
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
  readonly session = inject(Session);

  /**
   * The list is already scoped server-side — an Admin gets every token, a Member
   * only their own — so nothing here filters it a second time.
   */
  private readonly tokensResource = httpResource<ApiToken[]>(() => `${API_BASE}/tokens`, {
    defaultValue: [],
  });
  readonly tokens = this.tokensResource.value;

  readonly createdToken = signal<ApiToken | null>(null);
  readonly copied = signal<string | null>(null);

  /**
   * Scopes offered, by role: `artifacts:write` writes to Installation resources
   * and is Admin-only, and the server rejects a Member who asks for it anyway.
   * Offering only what the caller may grant keeps the two in step.
   */
  readonly scopeOptions = computed(() =>
    this.session.isAdmin() ? ALL_SCOPES : ALL_SCOPES.filter((scope) => !scope.adminOnly),
  );

  readonly ownershipOptions: readonly { value: Ownership; label: string }[] = [
    { value: 'personal', label: 'Personal' },
    { value: 'installation', label: 'Installation' },
  ];

  readonly ownershipLabel = (value: Ownership): string =>
    this.ownershipOptions.find((option) => option.value === value)?.label ?? value;

  private readonly model = signal(blankToken());

  readonly tokenForm = form(
    this.model,
    (path) => {
      required(path.name, { message: 'Token name is required.' });
      // A token carrying no scope authenticates nothing. The tree validator keeps
      // the error on the fieldset rather than on either checkbox.
      validateTree(path.scopes, ({ value }) =>
        Object.values(value()).some(Boolean)
          ? null
          : { kind: 'atLeastOneScope', message: 'Select at least one scope.' },
      );
    },
    {
      submission: {
        action: async () => {
          const model = this.model();
          try {
            const created = await firstValueFrom(
              this.api.createToken({
                name: model.name,
                scopes: this.scopeOptions()
                  .map((scope) => scope.value)
                  .filter((scope) => model.scopes[scope]),
                personal: model.ownership === 'personal',
              }),
            );
            this.createdToken.set(created);
            this.tokenForm().reset(blankToken());
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

  hasScope(token: ApiToken, scope: TokenScope): boolean {
    return token.scopes.includes(scope);
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

  /**
   * A paste-ready MCP client configuration for the token just revealed. The URL
   * comes from the creation response rather than `location.origin`, which loses a
   * reverse-proxy sub-path and is the piece people most often assemble wrongly by
   * hand. Computed rather than a method: the template reads it three times.
   */
  readonly mcpSnippet = computed(() => {
    const created = this.createdToken();
    if (!created) {
      return '';
    }
    return JSON.stringify(
      {
        mcpServers: {
          outpost: {
            type: 'http',
            url: created.mcp_url,
            headers: { Authorization: `Bearer ${created.token}` },
          },
        },
      },
      null,
      2,
    );
  });

  copy(text: string): void {
    void navigator.clipboard.writeText(text).then(() => {
      this.copied.set(text);
      setTimeout(() => this.copied.set(null), 1500);
    });
  }
}

/**
 * A fresh create form: `telemetry:read` and a Personal Token, because that is the
 * agent-onboarding path; an Admin minting a CI credential switches both.
 */
function blankToken(): {
  name: string;
  scopes: Record<TokenScope, boolean>;
  ownership: Ownership;
} {
  return {
    name: '',
    scopes: { 'telemetry:read': true, 'artifacts:write': false },
    ownership: 'personal',
  };
}

const ALL_SCOPES: readonly {
  value: TokenScope;
  label: string;
  hint: string;
  adminOnly: boolean;
}[] = [
  {
    value: 'telemetry:read',
    label: 'telemetry:read',
    hint: 'Read telemetry over the MCP Surface.',
    adminOnly: false,
  },
  {
    value: 'artifacts:write',
    label: 'artifacts:write',
    hint: 'Upload source maps from CI with sentry-cli.',
    adminOnly: true,
  },
];
