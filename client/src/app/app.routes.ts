import { Routes } from '@angular/router';

import { authGuard } from './auth/auth.guard';

/**
 * The foundation's routing: one public screen (login) and one protected screen.
 *
 * `authGuard` protects the **experience**, not the data: it keeps a screen from filling up with
 * `401`s. What protects the data is server-side — filter chain, L1, `@TenantId` — and would stay
 * that way if this file disappeared.
 */
export const routes: Routes = [
  {
    path: 'login',
    loadComponent: () => import('./auth/login/login').then((m) => m.Login),
    title: 'Login — Clinexa',
  },
  {
    path: '',
    canActivate: [authGuard],
    loadComponent: () => import('./home/home').then((m) => m.Home),
    title: 'Clinexa',
  },
  // Everything else falls back to the protected screen, so to the guard. The client-side
  // counterpart of deny-by-default: an unknown route never leads to an open screen.
  { path: '**', redirectTo: '' },
];
