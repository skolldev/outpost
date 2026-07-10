import { bootstrapApplication } from '@angular/platform-browser';
import * as Sentry from '@sentry/angular';
import { AppComponent } from './app/app.component';
import { appConfig } from './app/app.config';
import { RuntimeConfig } from './app/core/runtime-config';

async function bootstrap(): Promise<void> {
  // Runtime config instead of build-time environments: the same build works in
  // dev, prod-serve, and docker (where the entrypoint templates config.json).
  const cfg: RuntimeConfig = await fetch('/config.json').then((r) => r.json());

  Sentry.init({
    dsn: cfg.sentryDsn,
    environment: cfg.environment,
    release: cfg.release,
    sendDefaultPii: true,
    tracesSampleRate: 1.0,
    // Propagate sentry-trace/baggage to the demo backend so browser + server
    // transactions join into one distributed trace.
    tracePropagationTargets: [new URL(cfg.apiBase).host, /^\//],
    enableLogs: true,
    integrations: [
      Sentry.browserTracingIntegration(),
      Sentry.consoleLoggingIntegration({ levels: ['log', 'warn', 'error'] }),
    ],
  });

  await bootstrapApplication(AppComponent, appConfig(cfg));
}

bootstrap().catch((err) => console.error('bootstrap failed', err));
