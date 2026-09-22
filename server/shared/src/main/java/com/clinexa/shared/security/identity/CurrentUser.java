package com.clinexa.shared.security.identity;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The identity contract — the anti-corruption layer of the whole security foundation.
 * <p>
 * No business code ever reads Spring's {@code Authentication}, a {@code Jwt} or an
 * {@code HttpSession}: everything goes through this interface (INV-6). The day the mechanism
 * changes (session &rarr; OIDC with {@code SEC-12}), a single class is rewritten — the resolver —
 * and no controller, no service.
 * <p>
 * <strong>Two single points</strong>: one construction point ({@link CurrentUserResolver}) and one
 * reading point (injection into controllers and services).
 * <p>
 * <strong>{@link #assignments()} never lives in the principal.</strong> A session lives for hours;
 * a membership changes (hiring, departure, revocation) and must stop being valid immediately. So
 * they are read from the source of truth on each request and cached <em>for the duration of that
 * request only</em> — which is what the resolver does, and what INV-6 keeps honest.
 */
public interface CurrentUser {

	/** Identifier of the authenticated account — the only identity that is ever trusted (I2). */
	UUID accountId();

	/** {@code sub} of the identity provider. At the foundation, the account's email. */
	String subject();

	/** Empty at the foundation — see {@link GlobalRole}. */
	Set<GlobalRole> globalRoles();

	/** clinicId &rarr; roles held there. Read per request, never stored in the principal. */
	Map<UUID, Set<ClinicRole>> assignments();

	/** Roles held in one clinic, empty when the account is not a member of it. */
	default Set<ClinicRole> rolesIn(UUID clinicId) {
		return assignments().getOrDefault(clinicId, Set.of());
	}

	/** Whether the account may act at all inside that clinic — the question L1 asks (guide 6.1). */
	default boolean isAssignedTo(UUID clinicId) {
		return !rolesIn(clinicId).isEmpty();
	}

}
