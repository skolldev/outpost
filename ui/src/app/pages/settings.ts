import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';

import { Api } from '../core/api';
import { AppUser, Project, ProjectKey } from '../core/models';
import { Session } from '../core/session';

/** Settings (§9 page 6): projects & DSNs, users. Admin-only mutations. */
@Component({
  selector: 'app-settings',
  imports: [FormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="mb-6 flex gap-1 border-b border-slate-800 text-sm">
      @for (tab of tabs; track tab) {
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
                  <span [class]="key.is_active ? 'text-slate-200' : 'text-slate-600 line-through'">{{
                    key.dsn
                  }}</span>
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
                  >{{ angularSnippet(dsn) }}</pre
                >
                <pre
                  class="overflow-x-auto rounded bg-slate-900 p-3 text-xs leading-relaxed text-slate-300"
                  >{{ springSnippet(dsn) }}</pre
                >
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

  readonly tabs = ['projects', 'users'] as const;
  readonly activeTab = signal<'projects' | 'users'>('projects');

  readonly projects = signal<Project[]>([]);
  readonly keys = signal<ProjectKey[]>([]);
  readonly users = signal<AppUser[]>([]);
  readonly expandedProject = signal<number | null>(null);
  readonly copied = signal<string | null>(null);
  readonly error = signal<string | null>(null);

  newSlug = '';
  newPlatform = 'javascript-angular';
  newEmail = '';
  newPassword = '';
  newRole = 'member';

  constructor() {
    void this.reloadProjects();
    if (this.session.isAdmin()) {
      void firstValueFrom(this.api.users()).then((users) => this.users.set(users));
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
      await firstValueFrom(this.api.createProject(this.newSlug, this.newSlug, this.newPlatform || null));
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
