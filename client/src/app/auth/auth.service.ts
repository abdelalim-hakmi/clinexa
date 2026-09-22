import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, catchError, of, switchMap, tap, throwError } from 'rxjs';

import { Me, Problem } from './auth.models';

/**
 * The only place in the front end that talks about authentication.
 *
 * **It stores no token, and that is the point.** The mechanism chosen (`SEC-01`) is an **opaque**
 * session cookie: it carries no rights, only a key to state kept server-side. The browser attaches
 * it on its own, JavaScript cannot read it (`HttpOnly`), and a revocation takes effect on the next
 * request — something a non-expired self-contained token cannot do.
 *
 * Corollary: there is **nothing to put in `localStorage`**. Storing a token there, or even the
 * profile, hands the first XSS flaw exactly what the `HttpOnly` cookie refuses it. The state below
 * lives in memory and is rebuilt with a call to `GET /api/v1/me` at startup.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);

  private readonly _me = signal<Me | null>(null);
  private readonly _loaded = signal(false);

  /** The current identity, or `null`. Used to display, never to authorize. */
  readonly me = this._me.asReadonly();

  /** `true` once the session has been checked with the server (not guessed). */
  readonly loaded = this._loaded.asReadonly();

  readonly loggedIn = computed(() => this._me() !== null);

  /**
   * Asks the server who we are.
   *
   * This is the only source of truth: on a page reload, the cookie may still be there, may be
   * expired, may be revoked — only the server knows. A `401` is a normal answer here, not an error
   * to report.
   */
  refresh(): Observable<Me | null> {
    return this.http.get<Me>('/api/v1/me').pipe(
      tap((me) => {
        this._me.set(me);
        this._loaded.set(true);
      }),
      catchError(() => {
        this._me.set(null);
        this._loaded.set(true);
        return of(null);
      }),
    );
  }

  /**
   * Login.
   *
   * It starts with `GET /api/v1/auth/csrf`, and that round trip is not superfluous: Angular only
   * adds the `X-XSRF-TOKEN` header if it has **already seen** the cookie of the same name. A
   * visitor landing straight on `/login` has made no request yet — without this call, the `POST`
   * below would go out without the header and the server would answer `403`, with nothing to
   * explain it. This is the mechanism's #1 trap.
   *
   * A condition that stays outside this file: the SPA and the API must be **same-origin**,
   * otherwise Angular's XSRF interceptor attaches nothing at all. In development, the `/api` proxy
   * takes care of it.
   */
  login(email: string, password: string): Observable<Me> {
    return this.http.get('/api/v1/auth/csrf').pipe(
      // A failure here must not hide the real error: let the POST speak for itself.
      catchError(() => of(null)),
      switchMap(() =>
        this.http.post<Me>('/api/v1/auth/login', { email, password }),
      ),
      tap((me) => {
        this._me.set(me);
        this._loaded.set(true);
      }),
      catchError((error: HttpErrorResponse) => throwError(() => message(error))),
    );
  }

  /**
   * Logout. The session is destroyed server-side, so the cookie is worthless afterwards — for
   * every service at once, since they all read the same Redis. Nothing to clear client-side.
   */
  logout(): Observable<void> {
    return this.http.post<void>('/api/v1/auth/logout', {}).pipe(
      tap(() => this._me.set(null)),
      catchError(() => {
        // The session may already be invalid: local state must reflect "logged out" regardless,
        // otherwise the screen claims a session the server has forgotten.
        this._me.set(null);
        return of(void 0);
      }),
    );
  }
}

/**
 * The sentence to show the user.
 *
 * `detail` comes from the server, already written for a human (LLD 21 §3) and deliberately
 * sparing: it never says whether an account exists. It is not "improved" client-side.
 */
function message(error: HttpErrorResponse): string {
  const problem = error.error as Problem | undefined;
  if (problem?.detail) {
    return problem.detail;
  }
  return error.status === 0
    ? 'The server is unreachable. Check your connection.'
    : 'Login failed. Try again.';
}
