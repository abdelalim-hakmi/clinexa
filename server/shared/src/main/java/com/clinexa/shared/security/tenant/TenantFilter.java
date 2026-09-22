package com.clinexa.shared.security.tenant;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.clinexa.shared.security.identity.ClinicRole;
import com.clinexa.shared.security.identity.CurrentUser;
import com.clinexa.shared.security.identity.CurrentUserResolver;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * L1 — resolution and validation of the tenant (guide 6.1), and the only place a role becomes an
 * authority.
 * <p>
 * The sequence is deliberately in this order:
 * <ol>
 * <li>the account id comes from the <strong>authenticated principal</strong>, never from a header
 * and never from a parameter (I2, I4);</li>
 * <li>{@code clinicId} is read from the <strong>URL path</strong>
 * ({@code /api/v1/clinics/&#123;clinicId&#125;/...}) — the rejected alternative was an ambient
 * "current clinic" header, rejected because it makes the tenant invisible in a log and in a code
 * review;</li>
 * <li>the memberships are loaded (one call per request, memoized by the resolver);</li>
 * <li>a clinic that is not among them is a {@code 403}, <strong>immediately and without
 * exception</strong> — not a filtered empty result;</li>
 * <li>only then are {@link TenantContext} and the request's authorities positioned.</li>
 * </ol>
 * <strong>Why the authorities are posed here.</strong> A role belongs to the pair (account, clinic):
 * "being a practitioner" is meaningless until the clinic is known. So the principal carries none
 * (see {@code AuthenticatedAccount}), and this filter grants {@code ROLE_PRACTITIONER} and its
 * siblings for the duration of this request, in this clinic. That choice — rather than a bespoke
 * {@code AuthorizationManager} — is the one guide 7.1 asks to make explicitly, and it is what lets
 * each service write its matrix in the plain {@code hasRole(...)} DSL.
 * <p>
 * The swapped {@code Authentication} never reaches Redis: Spring Security 6+ only writes the
 * security context to the session when something explicitly saves it (which only the login does),
 * and the original context is restored in a {@code finally} anyway.
 */
public class TenantFilter extends OncePerRequestFilter {

	/**
	 * Only a full UUID counts as a clinic segment. Anything else under {@code /api/v1/clinics/}
	 * is not a tenant path, and falls to the matrix — whose last line denies it (I1).
	 */
	private static final Pattern CLINIC_PATH = Pattern.compile(
			"^/api/v1/clinics/([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})(?:/.*)?$");

	private static final Logger log = LoggerFactory.getLogger(TenantFilter.class);

	private final CurrentUserResolver currentUserResolver;

	private final TenantContext tenantContext;

	public TenantFilter(CurrentUserResolver currentUserResolver, TenantContext tenantContext) {
		this.currentUserResolver = currentUserResolver;
		this.tenantContext = tenantContext;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {

		Matcher clinic = CLINIC_PATH.matcher(path(request));
		if (!clinic.matches()) {
			// Not a tenant route: nothing to resolve, and nothing to grant. The matrix decides.
			chain.doFilter(request, response);
			return;
		}

		CurrentUser user = this.currentUserResolver.resolve().orElse(null);
		if (user == null) {
			// Unauthenticated: let the chain answer 401. A 403 here would say "wrong clinic" to
			// someone who has not even said who they are.
			chain.doFilter(request, response);
			return;
		}

		UUID clinicId = UUID.fromString(clinic.group(1));
		Set<ClinicRole> roles = user.rolesIn(clinicId);
		if (roles.isEmpty()) {
			log.debug("L1 denial: account {} is not assigned to clinic {}", user.accountId(), clinicId);
			throw new SecurityDenial(SecurityErrorCode.AUTH_CLINIC_NOT_ASSIGNED);
		}

		SecurityContext previousContext = SecurityContextHolder.getContext();
		this.tenantContext.set(clinicId);
		try {
			SecurityContextHolder.setContext(contextWithRoles(previousContext, roles));
			chain.doFilter(request, response);
		}
		finally {
			SecurityContextHolder.setContext(previousContext);
			this.tenantContext.clear();
		}
	}

	private SecurityContext contextWithRoles(SecurityContext previous, Set<ClinicRole> roles) {
		Authentication authentication = previous.getAuthentication();
		SecurityContext context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(new ClinicScopedAuthentication(authentication,
				roles.stream().map(ClinicRole::authority).map(SimpleGrantedAuthority::new).toList()));
		return context;
	}

	/** Path without the context path — the services run at the root, but do not assume it. */
	private static String path(HttpServletRequest request) {
		String uri = request.getRequestURI();
		String context = request.getContextPath();
		return (context != null && !context.isEmpty() && uri.startsWith(context)) ? uri.substring(context.length())
				: uri;
	}

}
