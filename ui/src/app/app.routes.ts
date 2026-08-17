import { Routes } from '@angular/router';

import { authGuard } from './core/auth.guard';

export const routes: Routes = [
  {
    path: 'login',
    loadComponent: () => import('./pages/login/login').then((m) => m.LoginPage),
  },
  {
    path: '',
    canActivate: [authGuard],
    loadComponent: () => import('./layout/shell').then((m) => m.Shell),
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'issues' },
      {
        path: 'issues',
        loadComponent: () => import('./pages/issues/issues').then((m) => m.IssuesPage),
      },
      {
        path: 'issues/:id',
        loadComponent: () => import('./pages/issues/issue-detail').then((m) => m.IssueDetailPage),
      },
      {
        path: 'logs',
        loadComponent: () => import('./pages/logs/logs').then((m) => m.LogsPage),
      },
      {
        path: 'traces',
        loadComponent: () => import('./pages/traces/traces').then((m) => m.TracesPage),
      },
      {
        path: 'traces/:traceId',
        loadComponent: () => import('./pages/traces/trace-detail').then((m) => m.TraceDetailPage),
      },
      {
        path: 'releases',
        loadComponent: () => import('./pages/releases/releases').then((m) => m.ReleasesPage),
      },
      {
        path: 'uptime',
        loadComponent: () => import('./pages/uptime/uptime').then((m) => m.UptimePage),
      },
      {
        // The signed-in user's own account. Authenticated but deliberately not
        // Admin-guarded, and deliberately not a Settings tab: /settings is
        // Admin-only in its entirety (see settings.routes.ts and the Member
        // definition in CONTEXT.md), so hanging this off it would mean
        // weakening or special-casing that guard just so a Member can change a
        // password. A top-level route keeps that invariant intact.
        path: 'account',
        loadComponent: () => import('./pages/account/account').then((m) => m.AccountPage),
      },
      {
        path: 'settings',
        loadChildren: () =>
          import('./pages/settings/settings.routes').then((m) => m.SETTINGS_ROUTES),
      },
    ],
  },
  { path: '**', redirectTo: '' },
];
