package com.clinexa.shared.security.tenant;

import java.io.Serial;
import java.util.Collection;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;

/**
 * The authenticated identity, seen through one clinic.
 * <p>
 * It wraps the session's {@code Authentication} and adds the authorities of the roles held
 * <em>in the clinic of the current request</em> — nothing else. {@link TenantFilter} installs it
 * for the duration of a request and removes it afterwards, which is why a role never leaks into the
 * stored session, and why a revocation takes effect on the very next request.
 * <p>
 * It carries no membership map: {@code CurrentUser} is the one way to read those.
 */
final class ClinicScopedAuthentication extends AbstractAuthenticationToken {

	@Serial
	private static final long serialVersionUID = 1L;

	private final Authentication origin;

	ClinicScopedAuthentication(Authentication origin, Collection<? extends GrantedAuthority> authorities) {
		super(authorities);
		this.origin = origin;
		setAuthenticated(true);
		setDetails(origin.getDetails());
	}

	@Override
	public Object getPrincipal() {
		return this.origin.getPrincipal();
	}

	/** Always {@code null}: credentials are erased at login and never re-read. */
	@Override
	public Object getCredentials() {
		return null;
	}

	@Override
	public String getName() {
		return this.origin.getName();
	}

}
