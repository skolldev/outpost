import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';

interface SettingsTab {
  path: string;
  label: string;
}

/**
 * Settings shell (§9 page 6): a tab bar over the Admin-managed installation
 * resources. Each tab is its own routed component (see settings.routes.ts);
 * the active tab is driven by the URL via routerLinkActive, so settings are
 * deep-linkable and consistent with the rest of the app's URL-as-state model.
 */
@Component({
  selector: 'app-settings',
  imports: [RouterLink, RouterLinkActive, RouterOutlet],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './settings.html',
})
export class SettingsPage {
  readonly tabs: readonly SettingsTab[] = [
    { path: 'projects', label: 'Projects' },
    { path: 'uptime-monitors', label: 'Uptime monitors' },
    { path: 'notification-channels', label: 'Notification channels' },
    { path: 'data-retention', label: 'Data retention' },
    { path: 'api-tokens', label: 'API tokens' },
    { path: 'outpost-users', label: 'Users' },
  ];
}
