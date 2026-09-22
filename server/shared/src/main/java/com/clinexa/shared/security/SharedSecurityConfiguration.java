package com.clinexa.shared.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.clinexa.shared.security.assignment.AssignmentsProvider;
import com.clinexa.shared.security.identity.ClinicRole;
import com.clinexa.shared.security.identity.CurrentUser;
import com.clinexa.shared.security.identity.CurrentUserResolver;
import com.clinexa.shared.security.identity.GlobalRole;
import com.clinexa.shared.security.identity.SessionCurrentUserResolver;
import com.clinexa.shared.security.tenant.SecurityDenial;
import com.clinexa.shared.security.tenant.SecurityErrorCode;
import com.clinexa.shared.security.tenant.TenantContext;
import com.clinexa.shared.security.tenant.TenantFilter;
import com.clinexa.shared.security.web.DenialHandler;
import com.clinexa.shared.security.web.ProblemResponse;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wires the shared primitives once, so that every service gets the <em>same</em> identity and tenant
 * behaviour from a single place — the point of {@code SEC-11}.
 * <p>
 * A service imports this and supplies exactly one thing of its own: an {@link AssignmentsProvider}.
 * {@code identity-service} reads its own table; every other service calls it over the internal API.
 * Everything downstream — the resolver, L1, the refusal bodies — is identical on both sides, which
 * is what makes "the same cookie works on identity and on care" true rather than hopeful.
 * <p>
 * The matrix, the ownership rules and the {@code SecurityFilterChain} itself stay in each service
 * (guide 4.5): they are the parts that legitimately differ.
 */
@Configuration(proxyBeanMethods = false)
public class SharedSecurityConfiguration {

	@Bean
	public TenantContext tenantContext() {
		return new TenantContext();
	}

	@Bean
	public CurrentUserResolver currentUserResolver(AssignmentsProvider assignmentsProvider) {
		return new SessionCurrentUserResolver(assignmentsProvider);
	}

	/**
	 * The single reading point of the identity contract: controllers and services inject
	 * {@link CurrentUser}, never an {@code Authentication} (INV-6).
	 * <p>
	 * It is a normal singleton holding no state: every call delegates to the resolver, which reads
	 * the current thread's security context and memoizes the memberships for the current request.
	 * That keeps it injectable anywhere — including in a bean built at start-up — and makes "no
	 * authenticated user" a {@code 403} at the point of use rather than a failure at wiring time.
	 */
	@Bean
	public CurrentUser currentUser(CurrentUserResolver resolver) {
		return new DelegatingCurrentUser(resolver);
	}

	@Bean
	public TenantFilter tenantFilter(CurrentUserResolver resolver, TenantContext tenantContext) {
		return new TenantFilter(resolver, tenantContext);
	}

	@Bean
	public ProblemResponse problemResponse(JsonMapper objectMapper) {
		return new ProblemResponse(objectMapper);
	}

	@Bean
	public DenialHandler denialHandler(ProblemResponse problemResponse) {
		return new DenialHandler(problemResponse);
	}

	/** Resolves the current identity on every call; refuses when there is none. */
	private record DelegatingCurrentUser(CurrentUserResolver resolver) implements CurrentUser {

		private CurrentUser current() {
			return this.resolver.resolve()
				.orElseThrow(() -> new SecurityDenial(SecurityErrorCode.AUTH_NOT_AUTHENTICATED));
		}

		@Override
		public java.util.UUID accountId() {
			return current().accountId();
		}

		@Override
		public String subject() {
			return current().subject();
		}

		@Override
		public java.util.Set<GlobalRole> globalRoles() {
			return current().globalRoles();
		}

		@Override
		public java.util.Map<java.util.UUID, java.util.Set<ClinicRole>> assignments() {
			return current().assignments();
		}

	}

}
