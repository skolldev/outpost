import { Routes } from '@angular/router';

import { adminGuard } from '../../core/admin.guard';

/**
 * Settings is an installation-management surface — Admin-only in its entirety
 * (see the Member definition in CONTEXT.md). One guard on the parent covers
 * every tab; each tab is a lazily-loaded child route.
 */
export const SETTINGS_ROUTES: Routes = [
  {
    path: '',
    canActivate: [adminGuard],
    loadComponent: () => import('./settings').then((m) => m.SettingsPage),
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'projects' },
      {
        path: 'projects',
        loadComponent: () => import('./projects').then((m) => m.ProjectsSettings),
      },
      {
        path: 'uptime-monitors',
        loadComponent: () => import('./uptime-monitors').then((m) => m.UptimeMonitorsSettings),
      },
      {
        path: 'notification-channels',
        loadComponent: () =>
          import('./notification-channels').then((m) => m.NotificationChannelsSettings),
      },
      {
        path: 'data-retention',
        loadComponent: () => import('./data-retention').then((m) => m.DataRetentionSettings),
      },
      {
        path: 'api-tokens',
        loadComponent: () => import('./api-tokens').then((m) => m.ApiTokensSettings),
      },
      {
        path: 'outpost-users',
        loadComponent: () => import('./outpost-users').then((m) => m.OutpostUsersSettings),
      },
    ],
  },
];
