import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';

import { Session } from '../../core/session';

interface SettingsTab {
  path: string;
  label: string;
  /** Admin-only tabs are guarded in settings.routes.ts; this only hides them. */
  adminOnly: boolean;
}

/**
 * Settings shell: a tab bar over the installation resources. Each tab is its own
 * routed component (see settings.routes.ts) and the active tab is driven by the
 * URL, so settings are deep-linkable and consistent with the rest of the app's
 * URL-as-state model.
 *
 * <p>A Member sees only API tokens — the one tab that is theirs, where they mint
 * a Personal Token for their own agent (ADR-0017).
 */
@Component({
  selector: 'app-settings',
  imports: [RouterLink, RouterLinkActive, RouterOutlet],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './settings.html',
})
export class SettingsPage {
  private readonly session = inject(Session);

  private readonly allTabs: readonly SettingsTab[] = [
    { path: 'projects', label: 'Projects', adminOnly: true },
    { path: 'uptime-monitors', label: 'Uptime monitors', adminOnly: true },
    { path: 'notification-channels', label: 'Notification channels', adminOnly: true },
    { path: 'data-retention', label: 'Data retention', adminOnly: true },
    { path: 'api-tokens', label: 'API tokens', adminOnly: false },
    { path: 'outpost-users', label: 'Users', adminOnly: true },
  ];

  readonly tabs = computed(() =>
    this.session.isAdmin() ? this.allTabs : this.allTabs.filter((tab) => !tab.adminOnly),
  );
}
