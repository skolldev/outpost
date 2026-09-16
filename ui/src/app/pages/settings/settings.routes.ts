import { inject } from '@angular/core';
import { Routes } from '@angular/router';

import { adminGuard } from '../../core/admin.guard';
import { Session } from '../../core/session';

/**
 * Every tab is Admin-only except API tokens (Member territory, ADR-0017), so
 * the guard sits on each tab rather than the parent. `authGuard` upstream has
 * already resolved the Session by the time the redirectTo below runs.
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
