package com.clinexa.shared.security.identity;

/**
 * The roles a {@code member} may hold <em>in one clinic</em> — never on the account itself (I3).
 * <p>
 * They are <strong>disjoint, never a hierarchy</strong> (I6, {@code SEC-06}): {@code CLINIC_ADMIN}
 * does not contain {@code PRACTITIONER}. A manager who also works the front desk holds two
 * memberships, written in the database, readable and revocable separately. Introducing a
 * {@code RoleHierarchy} bean would silently hand the clinical record to the administrative role —
 * INV-4 forbids it.
 * <p>
 * Deferred roles ({@code PATIENT}, {@code PLATFORM_ADMIN}, {@code LOCUM}, {@code NURSE}) are
 * deliberately absent: the model accepts them without migration, since the role is carried by the
 * member, but none of them exists at the foundation.
 */
public enum ClinicRole {

	RECEPTIONIST,
	PRACTITIONER,
	CLINIC_ADMIN;

	/** Spring Security spells a role {@code ROLE_X}; {@code hasRole("X")} looks that authority up. */
	public String authority() {
		return "ROLE_" + name();
	}

}
