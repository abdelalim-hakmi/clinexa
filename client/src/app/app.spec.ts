import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { App } from './app';
import { AuthService } from './auth/auth.service';

describe('App', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
  });

  it('should create the app', () => {
    const fixture = TestBed.createComponent(App);
    expect(fixture.componentInstance).toBeTruthy();
  });
});

describe('AuthService', () => {
  let auth: AuthService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    auth = TestBed.inject(AuthService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  /** The CSRF bootstrap that login does first. */
  function serveToken(): void {
    http
      .expectOne('/api/v1/auth/csrf')
      .flush({ headerName: 'X-XSRF-TOKEN', token: 'test-token' });
  }

  // The session cookie is opaque and HttpOnly: there is nothing to store client-side, and state
  // is rebuilt by asking the server. A 401 is a normal answer, not a failure.
  it('treats a 401 on /me as "not logged in", without an error', () => {
    let result: unknown = 'never called';
    auth.refresh().subscribe((me) => (result = me));

    http.expectOne('/api/v1/me').flush(null, { status: 401, statusText: 'Unauthorized' });

    expect(result).toBeNull();
    expect(auth.loggedIn()).toBe(false);
    expect(auth.loaded()).toBe(true);
  });

  // Without this call, Angular has never seen the XSRF-TOKEN cookie and so does not add the
  // header: the POST would come back 403 with no explanation. This is the mechanism's #1 trap.
  it('requests the CSRF token before posting the login', () => {
    auth.login('alice@clinic-a.ma', 'Clinexa!2026').subscribe({ error: () => undefined });

    const bootstrap = http.expectOne('/api/v1/auth/csrf');
    expect(bootstrap.request.method).toBe('GET');
    bootstrap.flush({ headerName: 'X-XSRF-TOKEN', token: 'test-token' });

    http.expectOne('/api/v1/auth/login').flush({ accountId: 'x', email: 'y', clinics: [] });
  });

  it('exposes identity and assignments after a login', () => {
    auth.login('erin@clinexa.ma', 'Clinexa!2026').subscribe();
    serveToken();

    const request = http.expectOne('/api/v1/auth/login');
    expect(request.request.method).toBe('POST');
    request.flush({
      accountId: '0193a000-0001-7000-8000-0000000000e0',
      email: 'erin@clinexa.ma',
      clinics: [
        { clinicId: '0193a000-0000-7000-8000-00000000000a', roles: ['PRACTITIONER'] },
        { clinicId: '0193a000-0000-7000-8000-00000000000b', roles: ['RECEPTIONIST'] },
      ],
    });

    expect(auth.loggedIn()).toBe(true);
    // The decisive fixture: one account, two clinics, two different roles (I3).
    expect(auth.me()?.clinics.length).toBe(2);
  });

  // The server never says whether the email exists; the screen must not reconstruct the
  // difference.
  it('surfaces the server sentence, without embellishing it', () => {
    let message: unknown = null;
    auth.login('unknown@example.com', 'x').subscribe({ error: (e) => (message = e) });
    serveToken();

    http.expectOne('/api/v1/auth/login').flush(
      {
        title: 'Authentication required',
        detail: 'Log in to access this resource.',
        status: 401,
        code: 'AUTH_NOT_AUTHENTICATED',
      },
      { status: 401, statusText: 'Unauthorized' },
    );

    expect(message).toBe('Log in to access this resource.');
    expect(auth.loggedIn()).toBe(false);
  });

  // If the bootstrap fails, login still goes out: it is up to the server to say no, otherwise
  // the real error is masked by ours.
  it('attempts the login even if the CSRF bootstrap fails', () => {
    auth.login('alice@clinic-a.ma', 'Clinexa!2026').subscribe({ error: () => undefined });

    http
      .expectOne('/api/v1/auth/csrf')
      .flush(null, { status: 503, statusText: 'Service Unavailable' });

    http.expectOne('/api/v1/auth/login').flush(null, { status: 403, statusText: 'Forbidden' });
  });

  it('considers itself logged out even if logout fails', () => {
    auth.logout().subscribe();
    http.expectOne('/api/v1/auth/logout').flush(null, { status: 500, statusText: 'Error' });
    expect(auth.loggedIn()).toBe(false);
  });
});
