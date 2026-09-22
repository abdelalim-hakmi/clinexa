package com.clinexa.shared.security.identity;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import com.clinexa.shared.security.assignment.AssignmentsProvider;

/**
 * {@link CurrentUserResolver} for the mechanism chosen by {@code SEC-01}: an opaque session cookie
 * backed by Redis. It is the single place that touches {@code SecurityContextHolder} (INV-6).
 * <p>
 * <strong>The memberships are resolved lazily and memoized per request.</strong> Lazily, because a
 * route that needs no tenant (the login, {@code /api/v1/me} aside) must not pay for a call to
 * {@code identity-service}; memoized per request, because reading them twice in one request would
 * double that cost, and caching them <em>beyond</em> the request would quietly re-create the option
 * already rejected in guide 4.4 — memberships in the principal, revocation no longer immediate.
 * <p>
 * The memo lives in the request attributes rather than in a request-scoped bean so that the same
 * code works inside a servlet filter, a controller and a service, and so that a thread without a
 * request (a consumer, an {@code @Async} task) simply gets nothing instead of an obscure failure.
 */
public class SessionCurrentUserResolver implements CurrentUserResolver {

	private static final String ATTRIBUTE = SessionCurrentUserResolver.class.getName() + ".CURRENT_USER";

	private final AssignmentsProvider assignmentsProvider;

	public SessionCurrentUserResolver(AssignmentsProvider assignmentsProvider) {
		this.assignmentsProvider = assignmentsProvider;
	}

	@Override
	public Optional<CurrentUser> resolve() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null || !authentication.isAuthenticated()
				|| !(authentication.getPrincipal() instanceof AuthenticatedAccount account)) {
			return Optional.empty();
		}
		return Optional.of(memoize(account));
	}

	private CurrentUser memoize(AuthenticatedAccount account) {
		RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
		if (attributes == null) {
			return fresh(account);
		}
		Object existing = attributes.getAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
		if (existing instanceof CurrentUser current && current.accountId().equals(account.accountId())) {
			return current;
		}
		CurrentUser current = fresh(account);
		attributes.setAttribute(ATTRIBUTE, current, RequestAttributes.SCOPE_REQUEST);
		return current;
	}

	private CurrentUser fresh(AuthenticatedAccount account) {
		return new SessionCurrentUser(account, () -> assignmentsProvider.assignmentsOf(account.accountId()));
	}

	/** Holds the identity eagerly and the memberships behind a one-shot supplier. */
	private static final class SessionCurrentUser implements CurrentUser {

		private final AuthenticatedAccount account;

		private final Supplier<Map<UUID, Set<ClinicRole>>> loading;

		private Map<UUID, Set<ClinicRole>> assignments;

		private SessionCurrentUser(AuthenticatedAccount account, Supplier<Map<UUID, Set<ClinicRole>>> loading) {
			this.account = account;
			this.loading = loading;
		}

		@Override
		public UUID accountId() {
			return account.accountId();
		}

		@Override
		public String subject() {
			return account.getUsername();
		}

		@Override
		public Set<GlobalRole> globalRoles() {
			return Set.of();
		}

		@Override
		public Map<UUID, Set<ClinicRole>> assignments() {
			if (assignments == null) {
				assignments = Map.copyOf(loading.get());
			}
			return assignments;
		}

	}

}
