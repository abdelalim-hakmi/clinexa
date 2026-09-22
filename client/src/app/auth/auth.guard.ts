import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { map } from 'rxjs';

import { AuthService } from './auth.service';

/**
 * Prevents showing a screen that could load nothing.
 *
 * **This guard is not a security measure, and must not be read as one.** All it protects is the
 * experience: without it, a logged-out user would watch a screen fill up with `401`s. The data
 * itself is protected server-side — filter chain, L1, `@TenantId` — and would stay protected if
 * this file disappeared. A route guard is bypassed by opening the dev tools; that is exactly why
 * security does not live here.
 *
 * It asks the server the first time rather than trusting local state: on a page reload, only the
 * server knows whether the cookie is still worth anything.
 */
export const authGuard: CanActivateFn = (_route, state) => {
  const auth = inject(AuthService);
  const router = inject(Router);

  const toLogin = () => router.createUrlTree(['/login'], { queryParams: { redirect: state.url } });

  if (auth.loaded()) {
    return auth.loggedIn() ? true : toLogin();
  }

  return auth.refresh().pipe(map((me) => (me ? true : toLogin())));
};
