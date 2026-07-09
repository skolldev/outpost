import { Routes } from '@angular/router';

export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'catalog' },
  {
    path: 'catalog',
    loadComponent: () => import('./pages/catalog.component').then((m) => m.CatalogComponent),
  },
  {
    path: 'checkout',
    loadComponent: () => import('./pages/checkout.component').then((m) => m.CheckoutComponent),
  },
];
