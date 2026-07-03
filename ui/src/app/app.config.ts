import { ApplicationConfig, inject, provideBrowserGlobalErrorListeners } from '@angular/core';
import {
  HttpErrorResponse,
  HttpInterceptorFn,
  provideHttpClient,
  withInterceptors,
} from '@angular/common/http';
import { Router } from '@angular/router';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { catchError, throwError } from 'rxjs';

import { routes } from './app.routes';

/** Redirect to /login when the session expires mid-use. */
const unauthorizedInterceptor: HttpInterceptorFn = (req, next) => {
  const router = inject(Router);
  return next(req).pipe(
    catchError((error: unknown) => {
      if (
        error instanceof HttpErrorResponse &&
        error.status === 401 &&
        !req.url.includes('/auth/')
      ) {
        void router.navigate(['/login'], { queryParams: { returnUrl: router.url } });
      }
      return throwError(() => error);
    }),
  );
};

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes, withComponentInputBinding()),
    provideHttpClient(withInterceptors([unauthorizedInterceptor])),
  ],
};
