import { bootstrapApplication } from '@angular/platform-browser';
import * as Sentry from '@sentry/angular';
import { AppComponent } from './app/app.component';
import { appConfig } from './app/app.config';
import { RuntimeConfig } from './app/core/runtime-config';

async function bootstrap(): Promise<void> {
  // Fetched at runtime so one build serves dev, prod-serve, and docker.
  const cfg: RuntimeConfig = await fetch('/config.json').then((r) => r.json());

  Sentry.init({
    dsn: cfg.sentryDsn,
    environment: cfg.environment,
    release: cfg.release,
    sendDefaultPii: true,
    tracesSampleRate: 1.0,
    // Lets browser and server transactions join into one distributed trace.
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
