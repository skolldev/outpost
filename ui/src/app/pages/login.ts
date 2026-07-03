import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';

import { Session } from '../core/session';

@Component({
  selector: 'app-login',
  imports: [FormsModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="flex min-h-screen items-center justify-center bg-slate-950">
      <form
        (ngSubmit)="submit()"
        class="w-full max-w-sm rounded-lg border border-slate-800 bg-slate-900 p-8"
      >
        <h1 class="mb-6 flex items-center gap-2 text-xl font-semibold text-white">
          <span class="text-amber-400">▲</span> Outpost
        </h1>
        <label class="mb-1 block text-sm text-slate-400" for="email">Email</label>
        <input
          id="email"
          name="email"
          type="email"
          required
          [(ngModel)]="email"
          class="mb-4 w-full rounded border border-slate-700 bg-slate-800 px-3 py-2 text-sm text-white focus:border-amber-500 focus:outline-none"
        />
        <label class="mb-1 block text-sm text-slate-400" for="password">Password</label>
        <input
          id="password"
          name="password"
          type="password"
          required
          [(ngModel)]="password"
          class="mb-6 w-full rounded border border-slate-700 bg-slate-800 px-3 py-2 text-sm text-white focus:border-amber-500 focus:outline-none"
        />
        @if (error()) {
          <p class="mb-4 text-sm text-red-400">{{ error() }}</p>
        }
        <button
          type="submit"
          [disabled]="busy()"
          class="w-full rounded bg-amber-500 px-3 py-2 text-sm font-medium text-slate-950 hover:bg-amber-400 disabled:opacity-50"
        >
          {{ busy() ? 'Signing in…' : 'Sign in' }}
        </button>
      </form>
    </div>
  `,
})
export class LoginPage {
  private readonly session = inject(Session);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);

  email = '';
  password = '';
  readonly error = signal<string | null>(null);
  readonly busy = signal(false);

  async submit(): Promise<void> {
    this.error.set(null);
    this.busy.set(true);
    try {
      await this.session.login(this.email, this.password);
      const returnUrl = this.route.snapshot.queryParamMap.get('returnUrl') ?? '/issues';
      await this.router.navigateByUrl(returnUrl);
    } catch {
      this.error.set('Invalid email or password.');
    } finally {
      this.busy.set(false);
    }
  }
}
