import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';

import { Api } from '../core/api';
import { ApiToken, AppUser, Project, ProjectKey } from '../core/models';
import { Session } from '../core/session';

/** Settings (§9 page 6): projects & DSNs, sentry-cli API tokens, users. Admin-only mutations. */
@Component({
  selector: 'app-settings',
  imports: [DatePipe, FormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="mb-6 flex gap-1 border-b border-slate-800 text-sm">
      @for (tab of tabs(); track tab) {
        <button
          (click)="activeTab.set(tab)"
          class="-mb-px border-b-2 px-4 py-2 capitalize"
          [class]="
            activeTab() === tab
              ? 'border-amber-400 text-white'
              : 'border-transparent text-slate-400 hover:text-white'
          "
        >
          {{ tab }}
        </button>
      }
    </div>

    @if (activeTab() === 'projects') {
      @if (session.isAdmin()) {
        <form (ngSubmit)="createProject()" class="mb-6 flex flex-wrap items-end gap-3">
          <div>
            <label class="mb-1 block text-xs text-slate-500" for="slug">Slug</label>
            <input
              id="slug"
              name="slug"
              [(ngModel)]="newSlug"
              placeholder="shop-frontend"
              pattern="[a-z0-9][a-z0-9-]*"
              required
              class="rounded border border-slate-700 bg-slate-800 px-3 py-1.5 text-sm"
            />
          </div>
          <div>
            <label class="mb-1 block text-xs text-slate-500" for="platform">Platform</label>
            <select
              id="platform"
              name="platform"
              [(ngModel)]="newPlatform"
              class="rounded border border-slate-700 bg-slate-800 px-2 py-1.5 text-sm"
            >
              <option value="javascript-angular">Angular</option>
              <option value="java-spring-boot">Spring Boot</option>
              <option value="">Other</option>
            </select>
          </div>
          <button
            type="submit"
            class="rounded bg-amber-500 px-3 py-1.5 text-sm font-medium text-slate-950 hover:bg-amber-400"
          >
            Create project
          </button>
          @if (error()) {
            <span class="text-sm text-red-400">{{ error() }}</span>
          }
        </form>
      }

      @for (project of projects(); track project.id) {
        <section class="mb-4 rounded-lg border border-slate-800">
          <button
            (click)="toggleProject(project)"
            class="flex w-full items-center gap-3 px-4 py-3 text-left"
          >
            <span class="font-medium text-white">{{ project.name }}</span>
            <span class="text-xs text-slate-500">{{ project.platform || 'no platform' }}</span>
            <span class="ml-auto text-xs text-slate-500">id {{ project.id }}</span>
          </button>
          @if (expandedProject() === project.id) {
            <div class="border-t border-slate-800 px-4 py-3">
              <div class="mb-3 flex items-center justify-between">
                <h3 class="text-sm font-semibold text-slate-200">DSN keys</h3>
                @if (session.isAdmin()) {
                  <button
                    (click)="addKey(project.id)"
                    class="rounded border border-slate-700 px-2 py-1 text-xs text-slate-300 hover:border-slate-500"
                  >
                    Add key (rotate)
                  </button>
                }
              </div>
              @for (key of keys(); track key.id) {
                <div
                  class="mb-2 flex flex-wrap items-center gap-2 rounded bg-slate-900 px-3 py-2 font-mono text-xs"
                >
                  <span
                    [class]="key.is_active ? 'text-slate-200' : 'text-slate-600 line-through'"
                    >{{ key.dsn }}</span
                  >
                  <button
                    (click)="copy(key.dsn)"
                    class="rounded border border-slate-700 px-1.5 py-0.5 font-sans text-slate-400 hover:border-slate-500"
                  >
                    {{ copied() === key.dsn ? 'Copied!' : 'Copy' }}
                  </button>
                  @if (session.isAdmin()) {
                    <button
                      (click)="setKeyActive(project.id, key)"
                      class="rounded border border-slate-700 px-1.5 py-0.5 font-sans text-slate-400 hover:border-slate-500"
                    >
                      {{ key.is_active ? 'Revoke' : 'Re-enable' }}
                    </button>
                  }
                </div>
              }
              @if (activeDsn(); as dsn) {
                <h3 class="mb-1 mt-4 text-sm font-semibold text-slate-200">SDK setup</h3>
                <p class="mb-2 text-xs text-slate-500">
                  Angular (main.ts) — or Spring Boot (application.yaml) below.
                </p>
                <pre
                  class="mb-3 overflow-x-auto rounded bg-slate-900 p-3 text-xs leading-relaxed text-slate-300"
                  >{{ angularSnippet(dsn) }}</pre>
                <pre
                  class="overflow-x-auto rounded bg-slate-900 p-3 text-xs leading-relaxed text-slate-300"
                  >{{ springSnippet(dsn) }}</pre>
              }
            </div>
          }
        </section>
      } @empty {
        <p class="py-8 text-center text-sm text-slate-500">
          No projects yet{{ session.isAdmin() ? ' — create one above' : '' }}.
        </p>
      }
    }

    @if (activeTab() === 'tokens') {
      <p class="mb-4 text-sm text-slate-400">
        API tokens authenticate <code class="rounded bg-slate-900 px-1">sentry-cli</code> source-map
        uploads from CI (scope <code class="rounded bg-slate-900 px-1">artifacts:write</code>).
      </p>
      <form (ngSubmit)="createToken()" class="mb-4 flex flex-wrap items-end gap-3">
        <div>
          <label class="mb-1 block text-xs text-slate-500" for="tokenName">Name</label>
          <input
            id="tokenName"
            name="tokenName"
            [(ngModel)]="newTokenName"
            placeholder="ci-shop-frontend"
            required
            class="rounded border border-slate-700 bg-slate-800 px-3 py-1.5 text-sm"
          />
        </div>
        <button
          type="submit"
          class="rounded bg-amber-500 px-3 py-1.5 text-sm font-medium text-slate-950 hover:bg-amber-400"
        >
          Create token
        </button>
        @if (error()) {
          <span class="text-sm text-red-400">{{ error() }}</span>
        }
      </form>

      @if (createdToken(); as created) {
        <div
          class="mb-4 rounded-lg border border-emerald-500/40 bg-emerald-500/10 px-4 py-3 text-sm"
        >
          <p class="mb-2 text-emerald-300">
            Token <span class="font-semibold">{{ created.name }}</span> created — copy it now, it is
            shown only once.
          </p>
          <div class="flex flex-wrap items-center gap-2 font-mono text-xs">
            <span class="rounded bg-slate-900 px-2 py-1 text-slate-200">{{ created.token }}</span>
            <button
              (click)="copy(created.token!)"
              class="rounded border border-slate-700 px-1.5 py-0.5 font-sans text-slate-400 hover:border-slate-500"
            >
              {{ copied() === created.token ? 'Copied!' : 'Copy' }}
            </button>
          </div>
          <pre
            class="mt-3 overflow-x-auto rounded bg-slate-900 p-3 text-xs leading-relaxed text-slate-300"
            >{{ cliSnippet(created.token!) }}</pre>
        </div>
      }

      <div class="overflow-hidden rounded-lg border border-slate-800">
        <table class="w-full text-sm">
          <thead class="border-b border-slate-800 bg-slate-900 text-left text-xs text-slate-500">
            <tr>
              <th class="px-4 py-2 font-medium">Name</th>
              <th class="px-4 py-2 font-medium">Scopes</th>
              <th class="px-4 py-2 font-medium">Created</th>
              <th class="px-4 py-2"></th>
            </tr>
          </thead>
          <tbody>
            @for (token of tokens(); track token.id) {
              <tr class="border-b border-slate-800/60 last:border-0">
                <td class="px-4 py-2">{{ token.name }}</td>
                <td class="px-4 py-2 font-mono text-xs text-slate-400">
                  {{ token.scopes.join(', ') }}
                </td>
                <td class="px-4 py-2 text-xs text-slate-400">
                  {{ token.created_at | date: 'mediumDate' }}
                </td>
                <td class="px-4 py-2 text-right">
                  <button
                    (click)="deleteToken(token)"
                    class="rounded border border-slate-700 px-1.5 py-0.5 text-xs text-slate-400 hover:border-red-500 hover:text-red-400"
                  >
                    Revoke
                  </button>
                </td>
              </tr>
            } @empty {
              <tr>
                <td colspan="4" class="px-4 py-6 text-center text-sm text-slate-500">
                  No tokens yet.
                </td>
              </tr>
            }
          </tbody>
        </table>
      </div>
    }

    @if (activeTab() === 'users') {
      @if (session.isAdmin()) {
        <form (ngSubmit)="createUser()" class="mb-6 flex flex-wrap items-end gap-3">
          <div>
            <label class="mb-1 block text-xs text-slate-500" for="email">Email</label>
            <input
              id="email"
              name="email"
              type="email"
              required
              [(ngModel)]="newEmail"
              class="rounded border border-slate-700 bg-slate-800 px-3 py-1.5 text-sm"
            />
          </div>
          <div>
            <label class="mb-1 block text-xs text-slate-500" for="password">Password</label>
            <input
              id="password"
              name="password"
              type="password"
              required
              minlength="8"
              [(ngModel)]="newPassword"
              class="rounded border border-slate-700 bg-slate-800 px-3 py-1.5 text-sm"
            />
          </div>
          <div>
            <label class="mb-1 block text-xs text-slate-500" for="role">Role</label>
            <select
              id="role"
              name="role"
              [(ngModel)]="newRole"
              class="rounded border border-slate-700 bg-slate-800 px-2 py-1.5 text-sm"
            >
              <option value="member">member</option>
              <option value="admin">admin</option>
            </select>
          </div>
          <button
            type="submit"
            class="rounded bg-amber-500 px-3 py-1.5 text-sm font-medium text-slate-950 hover:bg-amber-400"
          >
            Add user
          </button>
          @if (error()) {
            <span class="text-sm text-red-400">{{ error() }}</span>
          }
        </form>
      }
      <div class="overflow-hidden rounded-lg border border-slate-800">
        <table class="w-full text-sm">
          <thead class="border-b border-slate-800 bg-slate-900 text-left text-xs text-slate-500">
            <tr>
              <th class="px-4 py-2 font-medium">Email</th>
              <th class="px-4 py-2 font-medium">Role</th>
            </tr>
          </thead>
          <tbody>
            @for (user of users(); track user.id) {
              <tr class="border-b border-slate-800/60 last:border-0">
                <td class="px-4 py-2">{{ user.email }}</td>
                <td class="px-4 py-2 text-slate-400">{{ user.role }}</td>
              </tr>
            }
          </tbody>
        </table>
      </div>
    }
  `,
})
export class SettingsPage {
  private readonly api = inject(Api);
  readonly session = inject(Session);

  // API tokens are admin-only end to end, so members don't get the tab.
  readonly tabs = computed<('projects' | 'tokens' | 'users')[]>(() =>
    this.session.isAdmin() ? ['projects', 'tokens', 'users'] : ['projects', 'users'],
  );
  readonly activeTab = signal<'projects' | 'tokens' | 'users'>('projects');

  readonly projects = signal<Project[]>([]);
  readonly keys = signal<ProjectKey[]>([]);
  readonly users = signal<AppUser[]>([]);
  readonly tokens = signal<ApiToken[]>([]);
  readonly createdToken = signal<ApiToken | null>(null);
  readonly expandedProject = signal<number | null>(null);
  readonly copied = signal<string | null>(null);
  readonly error = signal<string | null>(null);

  newSlug = '';
  newPlatform = 'javascript-angular';
  newEmail = '';
  newPassword = '';
  newRole = 'member';
  newTokenName = '';

  constructor() {
    void this.reloadProjects();
    if (this.session.isAdmin()) {
      void firstValueFrom(this.api.users()).then((users) => this.users.set(users));
      void firstValueFrom(this.api.tokens()).then((tokens) => this.tokens.set(tokens));
    }
  }

  activeDsn(): string | null {
    return this.keys().find((k) => k.is_active)?.dsn ?? null;
  }

  angularSnippet(dsn: string): string {
    return `Sentry.init({
  dsn: "${dsn}",
  environment: "prod",                  // dev | qa | prod
  release: "my-app@" + APP_VERSION,
  tracesSampleRate: 1.0,
  integrations: [Sentry.browserTracingIntegration()],
  tracePropagationTargets: ["https://api.example.com"],
  enableLogs: true,
});`;
  }

  springSnippet(dsn: string): string {
    return `sentry:
  dsn: ${dsn}
  environment: prod
  release: my-app@\${APP_VERSION}
  traces-sample-rate: 1.0
  logs.enabled: true`;
  }

  async toggleProject(project: Project): Promise<void> {
    if (this.expandedProject() === project.id) {
      this.expandedProject.set(null);
      return;
    }
    this.expandedProject.set(project.id);
    this.keys.set(await firstValueFrom(this.api.projectKeys(project.id)));
  }

  async createProject(): Promise<void> {
    this.error.set(null);
    try {
      await firstValueFrom(
        this.api.createProject(this.newSlug, this.newSlug, this.newPlatform || null),
      );
      this.newSlug = '';
      await this.reloadProjects();
    } catch {
      this.error.set('Could not create project — check the slug.');
    }
  }

  async addKey(projectId: number): Promise<void> {
    await firstValueFrom(this.api.createProjectKey(projectId));
    this.keys.set(await firstValueFrom(this.api.projectKeys(projectId)));
  }

  async setKeyActive(projectId: number, key: ProjectKey): Promise<void> {
    await firstValueFrom(this.api.setKeyActive(projectId, key.id, !key.is_active));
    this.keys.set(await firstValueFrom(this.api.projectKeys(projectId)));
  }

  async createToken(): Promise<void> {
    this.error.set(null);
    try {
      const created = await firstValueFrom(this.api.createToken(this.newTokenName));
      this.createdToken.set(created);
      this.newTokenName = '';
      this.tokens.set(await firstValueFrom(this.api.tokens()));
    } catch {
      this.error.set('Could not create token.');
    }
  }

  async deleteToken(token: ApiToken): Promise<void> {
    await firstValueFrom(this.api.deleteToken(token.id));
    if (this.createdToken()?.id === token.id) {
      this.createdToken.set(null);
    }
    this.tokens.set(await firstValueFrom(this.api.tokens()));
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

  async createUser(): Promise<void> {
    this.error.set(null);
    try {
      await firstValueFrom(this.api.createUser(this.newEmail, this.newPassword, this.newRole));
      this.newEmail = '';
      this.newPassword = '';
      this.users.set(await firstValueFrom(this.api.users()));
    } catch {
      this.error.set('Could not create user.');
    }
  }

  copy(text: string): void {
    void navigator.clipboard.writeText(text).then(() => {
      this.copied.set(text);
      setTimeout(() => this.copied.set(null), 1500);
    });
  }

  private async reloadProjects(): Promise<void> {
    this.projects.set(await firstValueFrom(this.api.projects()));
  }
}
