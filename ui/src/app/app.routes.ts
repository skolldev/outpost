import { Routes } from '@angular/router';

import { authGuard } from './core/auth.guard';

export const routes: Routes = [
  {
    path: 'login',
    loadComponent: () => import('./pages/login').then((m) => m.LoginPage),
  },
  {
    path: '',
    canActivate: [authGuard],
    loadComponent: () => import('./layout/shell').then((m) => m.Shell),
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'issues' },
      {
        path: 'issues',
        loadComponent: () => import('./pages/issues').then((m) => m.IssuesPage),
      },
      {
        path: 'issues/:id',
        loadComponent: () => import('./pages/issue-detail').then((m) => m.IssueDetailPage),
      },
      {
        path: 'logs',
        loadComponent: () => import('./pages/logs').then((m) => m.LogsPage),
      },
      {
        path: 'traces',
        loadComponent: () => import('./pages/traces').then((m) => m.TracesPage),
      },
      {
        path: 'traces/:traceId',
        loadComponent: () => import('./pages/trace-detail').then((m) => m.TraceDetailPage),
      },
      {
        path: 'releases',
        loadComponent: () => import('./pages/releases').then((m) => m.ReleasesPage),
      },
      {
        path: 'uptime',
        loadComponent: () => import('./pages/uptime').then((m) => m.UptimePage),
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
