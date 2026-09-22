package com.clinexa.shared.security.identity;

/**
 * Roles carried by the account itself, across every clinic.
 * <p>
 * <strong>Empty at the security foundation, on purpose.</strong> The only candidate was
 * {@code PATIENT}, and {@code SEC-02} defers the patient portal: there is no patient account and no
 * patient session, so no global role exists. The type is kept so that {@link CurrentUser} has the
 * shape it will keep when the portal arrives — adding a constant then changes no signature.
 */
public enum GlobalRole {
	// Intentionally empty — see the class javadoc (SEC-02).
}
