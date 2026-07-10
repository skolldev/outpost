import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { DemoUserService } from './demo-user.service';

/** Sends the selected persona to the demo backend so its events carry the same user. */
export const demoUserInterceptor: HttpInterceptorFn = (req, next) => {
  const user = inject(DemoUserService).user();
  return next(user ? req.clone({ setHeaders: { 'X-Demo-User': user } }) : req);
};
