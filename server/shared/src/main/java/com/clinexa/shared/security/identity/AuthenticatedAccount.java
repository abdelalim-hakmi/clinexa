package com.clinexa.shared.security.identity;

import java.io.Serial;
import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * The authenticated principal, and everything the session is allowed to remember.
 * <p>
 * It lives in {@code shared} for one concrete reason: the session is stored in Redis and read back
 * by <em>every</em> service (guide 5.4). What the session holds must therefore be serializable and
 * <strong>byte-identical on both sides</strong> — one class, one module.
 * <p>
 * <strong>It deliberately carries no membership and no authority.</strong> A session lives for hours,
 * a membership can be revoked in a second: putting them here would keep a revoked person working until
 * their session expired (guide 4.4). Roles are read per request and posed on the request's
 * {@code Authentication} by the tenant filter (L1), for that request only.
 */
public final class AuthenticatedAccount implements UserDetails, Serializable {

	@Serial
	private static final long serialVersionUID = 1L;

	private final UUID accountId;

	private final String email;

	// Transient on purpose: the hash is needed to authenticate, never to stay in Redis.
	private final transient String passwordHash;

	private final boolean active;

	public AuthenticatedAccount(UUID accountId, String email, String passwordHash, boolean active) {
		this.accountId = Objects.requireNonNull(accountId, "accountId");
		this.email = Objects.requireNonNull(email, "email");
		this.passwordHash = passwordHash;
		this.active = active;
	}

	public UUID accountId() {
		return accountId;
	}

	@Override
	public String getUsername() {
		return email;
	}

	@Override
	public String getPassword() {
		return passwordHash;
	}

	/**
	 * Always empty. Being "a practitioner" means nothing on its own — one is a practitioner
	 * <em>of a given clinic</em>, and only L1 knows which clinic the request is about.
	 */
	@Override
	public Collection<? extends GrantedAuthority> getAuthorities() {
		return List.of();
	}

	@Override
	public boolean isEnabled() {
		return active;
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof AuthenticatedAccount other && accountId.equals(other.accountId);
	}

	@Override
	public int hashCode() {
		return accountId.hashCode();
	}

	@Override
	public String toString() {
		return "AuthenticatedAccount[" + accountId + "]";
	}

}
