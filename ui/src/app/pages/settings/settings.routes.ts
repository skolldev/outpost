import { inject } from '@angular/core';
import { Routes } from '@angular/router';

import { adminGuard } from '../../core/admin.guard';
import { Session } from '../../core/session';

/**
 * Settings manages installation-wide resources, so every tab is Admin-only —
 * except API tokens, which a Member reaches to mint a Personal Token for their
 * own agent (ADR-0017). The guard therefore sits on each Admin tab rather than
 * on the parent, and the landing tab depends on the role. `authGuard` on the
 * app shell has already resolved the Session by the time the redirect runs.
 */
export const SETTINGS_ROUTES: Routes = [
  {
    path: '',
    loadComponent: () => import('./settings').then((m) => m.SettingsPage),
    children: [
      {
        path: '',
        pathMatch: 'full',
        redirectTo: () => (inject(Session).isAdmin() ? 'projects' : 'api-tokens'),
      },
      {
        path: 'projects',
        canActivate: [adminGuard],
        loadComponent: () => import('./projects/projects').then((m) => m.ProjectsSettings),
      },
      {
        path: 'uptime-monitors',
        canActivate: [adminGuard],
        loadComponent: () =>
          import('./uptime-monitors/uptime-monitors').then((m) => m.UptimeMonitorsSettings),
      },
      {
        path: 'notification-channels',
        canActivate: [adminGuard],
        loadComponent: () =>
          import('./notification-channels/notification-channels').then(
            (m) => m.NotificationChannelsSettings,
          ),
      },
      {
        path: 'data-retention',
        canActivate: [adminGuard],
        loadComponent: () =>
          import('./data-retention/data-retention').then((m) => m.DataRetentionSettings),
      },
      {
        path: 'api-tokens',
        loadComponent: () => import('./api-tokens/api-tokens').then((m) => m.ApiTokensSettings),
      },
      {
        path: 'outpost-users',
        canActivate: [adminGuard],
        loadComponent: () =>
          import('./outpost-users/outpost-users').then((m) => m.OutpostUsersSettings),
      },
    ],
  },
];
