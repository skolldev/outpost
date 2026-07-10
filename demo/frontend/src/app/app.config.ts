import {
  ApplicationConfig,
  ErrorHandler,
  inject,
  provideAppInitializer,
} from "@angular/core";
import { provideHttpClient, withInterceptors } from "@angular/common/http";
import { provideRouter, Router } from "@angular/router";
import * as Sentry from "@sentry/angular";
import { routes } from "./app.routes";
import { demoUserInterceptor } from "./core/demo-user.interceptor";
import { RUNTIME_CONFIG, RuntimeConfig } from "./core/runtime-config";

export function appConfig(cfg: RuntimeConfig): ApplicationConfig {
  return {
    providers: [
      provideRouter(routes),
      provideHttpClient(withInterceptors([demoUserInterceptor])),
      { provide: RUNTIME_CONFIG, useValue: cfg },
      {
        provide: ErrorHandler,
        useValue: Sentry.createErrorHandler({ showDialog: false }),
      },
      { provide: Sentry.TraceService, deps: [Router] },
      // Instantiating TraceService is what starts navigation transactions.
      provideAppInitializer(() => {
        inject(Sentry.TraceService);
      }),
    ],
  };
}
