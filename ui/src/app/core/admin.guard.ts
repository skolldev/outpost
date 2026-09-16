import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';

import { Session } from './session';

/**
 * Gates a route to Admins for UX only — the real boundary is server-side; this just keeps Members off a screen they can't use.
 */
export const adminGuard: CanActivateFn = async () => {
  const session = inject(Session);
  const router = inject(Router);
  await session.ensureLoaded();
  return session.isAdmin() ? true : router.createUrlTree(['/issues']);
};
