package com.clinexa.care.support;

import java.util.Set;

import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.stereotype.Component;

import com.clinexa.shared.security.fixtures.SecurityFixtures.AccountFixture;
import com.clinexa.shared.security.identity.AuthenticatedAccount;

import jakarta.servlet.http.Cookie;

/**
 * Opens a session for a fixture the way {@code identity-service} would, and hands back the cookie.
 * <p>
 * <strong>This is not a shortcut — it is the point.</strong> {@code care-service} has no sign-in
 * route: it owns no identity data, and {@code SEC-01} puts the one session-issuing endpoint in
 * {@code identity-service}. So a test here has to do what the other service does: write a session
 * into the shared Redis, holding a {@code SecurityContext} whose principal is the shared
 * {@link AuthenticatedAccount}, and present the resulting cookie.
 * <p>
 * Which means these tests also prove the claim guide 5.4 makes and nothing else here would check:
 * <strong>a session written by one service is readable by the other</strong>. Had the principal not
 * lived in {@code shared}, or not been serializable, this would fail with a deserialization error
 * rather than with a clear message — the "object not serializable in the session, breaks silently on
 * another service" trap of {@code 02-authentication} §6.
 * <p>
 * The cookie is produced by the application's own {@link CookieSerializer}, so its name and its
 * encoding are the real ones rather than a guess.
 */
@Component
public class SessionsCare {

	private final SessionRepository<? extends Session> sessions;

	private final CookieSerializer cookies;

	public SessionsCare(SessionRepository<? extends Session> sessions, CookieSerializer cookies) {
		this.sessions = sessions;
		this.cookies = cookies;
	}

	/** A session cookie for this account, valid for every service reading the same Redis. */
	public Cookie login(AccountFixture account) {
		Session session = create(account);
		MockHttpServletRequest request = new MockHttpServletRequest();
		MockHttpServletResponse response = new MockHttpServletResponse();
		this.cookies.writeCookieValue(new CookieSerializer.CookieValue(request, response, session.getId()));
		Cookie[] written = response.getCookies();
		if (written.length != 1) {
			throw new IllegalStateException("The cookie serializer did not write exactly one");
		}
		return written[0];
	}

	private <S extends Session> S create(AccountFixture account) {
		@SuppressWarnings("unchecked")
		SessionRepository<S> repository = (SessionRepository<S>) this.sessions;
		S session = repository.createSession();
		SecurityContext context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
				new AuthenticatedAccount(account.id(), account.email(), null, true), null, Set.of()));
		// The very attribute name HttpSessionSecurityContextRepository reads on the other side.
		session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
		repository.save(session);
		return session;
	}

}
