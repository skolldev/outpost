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
        path: 'performance',
        loadComponent: () =>
          import('./pages/performance/performance').then((m) => m.PerformancePage),
      },
      {
        // Query params, not a path segment: transaction names contain slashes; an absent op means the null-op group.
        path: 'performance/group',
        loadComponent: () =>
          import('./pages/performance/transaction-group-detail').then(
            (m) => m.TransactionGroupDetailPage,
          ),
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
        // Deliberately not Admin-guarded or under Settings: this is the user's own account, not an Installation setting.
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
