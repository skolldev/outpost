import { Injectable, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';

import { Api } from './api';
import { SessionUser } from './models';
import { PostHogLogService } from './posthog-log.service';
import { PostHogService } from './posthog.service';

@Injectable({ providedIn: 'root' })
export class Session {
  private readonly api = inject(Api);
  private readonly router = inject(Router);
  private readonly posthogService = inject(PostHogService);
  private readonly posthogLogService = inject(PostHogLogService);

  readonly user = signal<SessionUser | null>(null);
  private loaded = false;

  /** Resolves the current user from the session cookie, once per app load. */
  async ensureLoaded(): Promise<SessionUser | null> {
    if (!this.loaded) {
      try {
        this.user.set(await firstValueFrom(this.api.me()));
        this.posthogLogService.info('Authenticated session restored', {
          authentication_method: 'session_cookie',
        });
      } catch {
        this.user.set(null);
      }
      this.loaded = true;
    }
    return this.user();
  }

  async login(email: string, password: string): Promise<void> {
    this.user.set(await firstValueFrom(this.api.login(email, password)));
    this.loaded = true;
    this.posthogService.posthog.capture('user_logged_in');
    this.posthogLogService.info('User authentication completed', {
      authentication_method: 'password',
    });
  }

  async logout(): Promise<void> {
    try {
      await firstValueFrom(this.api.logout());
      this.posthogService.posthog.capture('user_logged_out');
      this.posthogLogService.info('User session ended');
    } finally {
      this.user.set(null);
      await this.router.navigateByUrl('/login');
    }
  }

  isAdmin(): boolean {
    return this.user()?.role === 'admin';
  }
}
