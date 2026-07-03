import { Injectable, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';

import { Api } from './api';
import { SessionUser } from './models';

@Injectable({ providedIn: 'root' })
export class Session {
  private readonly api = inject(Api);
  private readonly router = inject(Router);

  readonly user = signal<SessionUser | null>(null);
  private loaded = false;

  /** Resolves the current user from the session cookie, once per app load. */
  async ensureLoaded(): Promise<SessionUser | null> {
    if (!this.loaded) {
      try {
        this.user.set(await firstValueFrom(this.api.me()));
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
  }

  async logout(): Promise<void> {
    try {
      await firstValueFrom(this.api.logout());
    } finally {
      this.user.set(null);
      await this.router.navigateByUrl('/login');
    }
  }

  isAdmin(): boolean {
    return this.user()?.role === 'admin';
  }
}
