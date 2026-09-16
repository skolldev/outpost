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
 * Settings shell: a tab bar over the installation resources, driven by the URL
 * so tabs are deep-linkable. A Member sees only API tokens, to mint a Personal
 * Token for their own agent (ADR-0017).
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
