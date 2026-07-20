import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';

import { Session } from './session';

/**
 * Gates a route to Admins. UX only — the real boundary is server-side (admin
 * mutations are API-enforced); this just keeps Members off a screen they can do
 * nothing useful on and bounces them to the default view.
 */
export const adminGuard: CanActivateFn = async () => {
  const session = inject(Session);
  const router = inject(Router);
  await session.ensureLoaded();
  return session.isAdmin() ? true : router.createUrlTree(['/issues']);
};
