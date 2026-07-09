import { Injectable, signal } from '@angular/core';
import * as Sentry from '@sentry/angular';

export const PERSONAS = ['ada', 'grace', 'linus'] as const;
export type Persona = (typeof PERSONAS)[number];

@Injectable({ providedIn: 'root' })
export class DemoUserService {
  readonly user = signal<Persona | null>(null);

  signIn(persona: Persona): void {
    this.user.set(persona);
    Sentry.setUser({
      id: persona,
      username: persona,
      email: `${persona}@example.com`,
    });
    Sentry.logger.info(Sentry.logger.fmt`User ${persona} signed in`);
  }

  signOut(): void {
    Sentry.logger.info(Sentry.logger.fmt`User ${this.user() ?? 'unknown'} signed out`);
    this.user.set(null);
    Sentry.setUser(null);
  }
}
