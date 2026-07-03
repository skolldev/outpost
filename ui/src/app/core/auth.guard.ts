import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';

import { Session } from './session';

export const authGuard: CanActivateFn = async (_route, state) => {
  const session = inject(Session);
  const router = inject(Router);
  if (await session.ensureLoaded()) {
    return true;
  }
  return router.createUrlTree(['/login'], { queryParams: { returnUrl: state.url } });
};
