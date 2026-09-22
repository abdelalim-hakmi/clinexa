package com.clinexa.shared.security.assignment;

import java.io.Serial;

import com.clinexa.shared.security.tenant.SecurityDenial;
import com.clinexa.shared.security.tenant.SecurityErrorCode;

/**
 * The source of truth for memberships could not be reached — so the request is refused.
 * <p>
 * This is the fail-closed of guide 8.3, and it is a design choice rather than an accident: once
 * every tenant route depends on {@code identity-service}, the tempting shortcut is "it is down,
 * let it through this once". That shortcut serves data with no tenant filter at all. Refusing with
 * a {@code 403} is the only safe answer, and criterion 12 proves it by actually cutting the service.
 * <p>
 * The matching operational consequence is written in guide 8.6: {@code identity-service} runs with
 * at least two instances from V0 on, because its availability now conditions every tenant route of
 * every service.
 */
public class AssignmentsUnavailableException extends SecurityDenial {

	@Serial
	private static final long serialVersionUID = 1L;

	public AssignmentsUnavailableException(Throwable cause) {
		super(SecurityErrorCode.AUTH_ASSIGNMENTS_UNAVAILABLE, cause);
	}

}
