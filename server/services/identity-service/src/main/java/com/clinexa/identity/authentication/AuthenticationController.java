package com.clinexa.identity.authentication;

import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.DeferredCsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.clinexa.shared.security.identity.CurrentUser;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;

/**
 * The only place in the whole system that creates a session (guide, step 4 map).
 * <p>
 * The mechanism is the one {@code SEC-01} chose: an <strong>opaque session cookie</strong> backed by
 * Redis, not a self-contained token. The cookie holds no right at all, only a key to server-side
 * state — which is what makes a revocation effective immediately, unlike a JWT that stays valid
 * until it expires. Every other service reads that same session from the same Redis; the gateway
 * forwards the cookie and reads nothing.
 * <p>
 * This class is allowed to touch {@code SecurityContextHolder}, the session and the context
 * repository — it <em>is</em> the authentication mechanism. Everywhere else, INV-6 forbids it, and
 * the inventory test exempts this package by name.
 */
@RestController
class AuthenticationController {

	private final AuthenticationManager authenticationManager;

	private final SecurityContextRepository securityContextRepository;

	private final SessionAuthenticationStrategy sessionAuthenticationStrategy;

	private final CurrentUser currentUser;

	AuthenticationController(AuthenticationManager authenticationManager,
			SecurityContextRepository securityContextRepository,
			SessionAuthenticationStrategy sessionAuthenticationStrategy, CurrentUser currentUser) {
		this.authenticationManager = authenticationManager;
		this.securityContextRepository = securityContextRepository;
		this.sessionAuthenticationStrategy = sessionAuthenticationStrategy;
		this.currentUser = currentUser;
	}

	/**
	 * Hands a client its CSRF token — the route that makes the sign-in reachable at all.
	 * <p>
	 * <strong>Why it exists.</strong> A browser only sends the {@code X-XSRF-TOKEN} header if it has
	 * already <em>seen</em> the cookie of the same name. Someone landing straight on the login page
	 * has made no request yet, so their first {@code POST} would carry no token and be refused with
	 * a {@code 403} that explains nothing — the classic first trap of cookie-based CSRF. This route
	 * is a deterministic place to get one, instead of hoping some earlier response carried it.
	 * <p>
	 * <strong>Why a body and not a {@code 204}.</strong> So that a client with no cookie jar — the
	 * {@code _dev} smoke test, curl, an integration test — can read the token too, without parsing
	 * {@code Set-Cookie}. For a browser, the cookie remains the contract.
	 * <p>
	 * <strong>Why returning the token is not a leak.</strong> A CSRF token is not a credential. It
	 * proves a request came from a page able to read the cookie, which is precisely what another
	 * site cannot do — and that cookie is deliberately readable by JavaScript, since the SPA has to
	 * copy it into a header.
	 */
	@GetMapping("/api/v1/auth/csrf")
	CsrfTokenResponse csrf(HttpServletRequest request) {
		// The DEFERRED token, not the one in the CsrfToken attribute. The two are different values:
		// the attribute holds the XOR-masked form, which is what a plain HTML form posts as the
		// _csrf parameter, while the header is checked against the RAW token — the one the cookie
		// carries. Returning the masked value here would hand clients a token the chain then
		// rejects, as a 403 with nothing to explain it.
		DeferredCsrfToken deferred = (DeferredCsrfToken) request.getAttribute(DeferredCsrfToken.class.getName());
		CsrfToken token = (deferred != null) ? deferred.get() : (CsrfToken) request.getAttribute(CsrfToken.class.getName());
		return new CsrfTokenResponse(token.getHeaderName(), token.getToken());
	}

	/**
	 * @param headerName the header to copy the token into — named rather than assumed, so a client
	 * never hardcodes it
	 */
	record CsrfTokenResponse(String headerName, String token) {
	}

	/**
	 * Signs in and opens the session.
	 * <p>
	 * The session id is regenerated on success by the injected {@code SessionAuthenticationStrategy}
	 * — called explicitly, because the chain's {@code changeSessionId()} setting only covers logins
	 * done by a filter. Without it, an attacker able to plant a cookie before the sign-in still
	 * holds a valid one after it (session fixation); {@code AuthenticationTest} pins this.
	 * <p>
	 * A failed sign-in raises an {@code AuthenticationException}, which the shared entry point turns
	 * into a {@code 401} saying only that authentication is required. It never reveals whether the
	 * email exists — that would make this route an account-existence oracle.
	 */
	@PostMapping("/api/v1/auth/login")
	ResponseEntity<MeDto> login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest,
			HttpServletResponse response) {
		Authentication authentication = this.authenticationManager
			.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(request.email(), request.password()));

		// Before anything is stored: a session that existed before the sign-in must not survive it
		// under the same id (session fixation). The chain's own changeSessionId() setting does not
		// run for a login done in a controller, so this call is what actually protects it.
		this.sessionAuthenticationStrategy.onAuthentication(authentication, httpRequest, response);

		SecurityContext context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(authentication);
		SecurityContextHolder.setContext(context);
		// Explicit save: since Spring Security 6 the context is persisted only when asked. This is
		// the one call that does it — which is also why the roles L1 grants for the duration of a
		// request never end up in the stored session.
		this.securityContextRepository.saveContext(context, httpRequest, response);

		return ResponseEntity.ok(MeDto.of(this.currentUser));
	}

	/**
	 * Signs out: the server-side session is destroyed, so the cookie is worthless immediately — for
	 * every service at once, since they all read the same Redis. Nothing to revoke, nothing to
	 * blacklist; that is the return on the opaque-cookie choice.
	 */
	@PostMapping("/api/v1/auth/logout")
	ResponseEntity<Void> logout(HttpServletRequest request) {
		HttpSession session = request.getSession(false);
		if (session != null) {
			session.invalidate();
		}
		SecurityContextHolder.clearContext();
		return ResponseEntity.noContent().build();
	}

	/**
	 * Who the caller is, and which clinics it may act in (FR-IAM-07).
	 * <p>
	 * It reads {@link CurrentUser}, so the members come from the database on this very request: an
	 * account revoked a second ago no longer appears here.
	 */
	@GetMapping("/api/v1/me")
	MeDto me() {
		return MeDto.of(this.currentUser);
	}

}
