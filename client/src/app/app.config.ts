import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideHttpClient, withXsrfConfiguration } from '@angular/common/http';
import { provideRouter, withComponentInputBinding } from '@angular/router';

import { routes } from './app.routes';

/**
 * `withXsrfConfiguration` is the mandatory counterpart to the session cookie (`SEC-01`).
 *
 * Cookie-based authentication is inherently vulnerable to cross-site request forgery: the browser
 * attaches the cookie to *any* request to the origin, including one triggered from another site.
 * Angular's interceptor copies the `XSRF-TOKEN` cookie — which the server makes readable by
 * JavaScript, unlike the session cookie — into the `X-XSRF-TOKEN` header, which another site
 * cannot do.
 *
 * The names are spelled out explicitly even though they are the defaults: they are what the
 * Spring chain expects, and seeing them here saves a long search the day a `POST` answers `403`
 * with no clear message.
 *
 * **Non-negotiable condition: the SPA and the API must be same-origin.** Otherwise Angular
 * attaches nothing at all. In development, the `proxy.conf.json` proxy (`/api` → `:9000`) takes
 * care of it: the browser only ever talks to `localhost:4200`.
 */
export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes, withComponentInputBinding()),
    provideHttpClient(
      withXsrfConfiguration({
        cookieName: 'XSRF-TOKEN',
        headerName: 'X-XSRF-TOKEN',
      }),
    ),
  ],
};
