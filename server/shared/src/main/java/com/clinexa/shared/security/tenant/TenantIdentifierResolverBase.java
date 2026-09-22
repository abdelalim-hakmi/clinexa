package com.clinexa.shared.security.tenant;

import java.util.UUID;

import org.hibernate.context.spi.CurrentTenantIdentifierResolver;

/**
 * L2 — what makes {@code @TenantId} filter every {@code SELECT} and fill every {@code INSERT}
 * without a developer thinking about it (guide 6.2). Hibernate calls it when a session opens.
 * <p>
 * <strong>Why it returns {@link TenantContext#NONE} instead of throwing.</strong> Hibernate asks
 * once per <em>session</em>, not per entity — and a session is opened long before anyone knows
 * whether the request will touch a tenant table. Throwing here would also break the two reads the
 * foundation needs before a tenant exists: the {@code account} of the login, and the {@code member}
 * rows L1 reads by {@code account_id} ({@code SEC-13}). So the fail-closed lives in the
 * <em>value</em>: a tenant id no clinic can have. A tenant entity read under it returns nothing, a
 * write under it is rejected by the database (foreign key or {@code CHECK}, see
 * {@link TenantContext#NONE}) — never "no filter, therefore everything", which is
 * the single most expensive bug of any multi-tenant architecture.
 * <p>
 * The {@code 403} of criterion 10 is not this class's job: it belongs to L1 and to
 * {@link TenantContext#requireClinicId()}. This layer is the net underneath them, not a substitute.
 */
public class TenantIdentifierResolverBase implements CurrentTenantIdentifierResolver<UUID> {

	private final TenantContext tenantContext;

	public TenantIdentifierResolverBase(TenantContext tenantContext) {
		this.tenantContext = tenantContext;
	}

	@Override
	public UUID resolveCurrentTenantIdentifier() {
		return tenantContext.clinicId().orElse(TenantContext.NONE);
	}

	/**
	 * Never. A "root" tenant is Hibernate's own way of bypassing the filter — exactly the
	 * fail-open this layer exists to prevent.
	 */
	@Override
	public boolean isRoot(UUID tenantId) {
		return false;
	}

	/** A session bound to one clinic must not be reused under another. */
	@Override
	public boolean validateExistingCurrentSessions() {
		return true;
	}

}
