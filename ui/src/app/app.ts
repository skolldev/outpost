import { ChangeDetectionStrategy, Component, inject, isDevMode, OnInit } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { HlmToasterImports } from '@spartan-ng/helm/sonner';
import { environment } from '../environments/environment';
import { PostHogLogService } from './core/posthog-log.service';
import { PostHogService } from './core/posthog.service';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, HlmToasterImports],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './app.html',
})
export class App implements OnInit {
  private readonly posthogService = inject(PostHogService);
  private readonly posthogLogService = inject(PostHogLogService);

  ngOnInit(): void {
    if (!environment.posthogKey) {
      if (isDevMode()) {
        throw new Error(
          'NG_APP_POSTHOG_PROJECT_TOKEN variable required by PostHog is missing or un-configured, this causes events to be silently missed. This error stops appearing once NG_APP_POSTHOG_PROJECT_TOKEN is configured',
        );
      }
      return;
    }

    if (!environment.posthogHost) {
      if (isDevMode()) {
        throw new Error(
          'NG_APP_POSTHOG_HOST variable required by PostHog is missing or un-configured, this causes events to be silently missed. This error stops appearing once NG_APP_POSTHOG_HOST is configured',
        );
      }
      return;
    }

    this.posthogService.init(environment.posthogKey, {
      api_host: environment.posthogHost,
      capture_exceptions: true,
      logs: {
        serviceName: 'outpost-ui',
        environment: isDevMode() ? 'development' : 'production',
      },
    });
    this.posthogLogService.info('PostHog log capture initialized');
  }
}
