package com.clinexa.shared.security.tenant;

/**
 * The business error codes a security refusal may carry, as catalogued in LLD 21 §3.1.
 * <p>
 * They exist so that the three refusals of the foundation stay <em>distinguishable in a log</em>:
 * {@code 401} "I do not know who you are", {@code 403 AUTH_CLINIC_NOT_ASSIGNED} "I know who you are
 * but not for this clinic", {@code 403 AUTH_ROLE_INSUFFICIENT} "I know who you are and for which
 * clinic, but you do not hold that right". Collapsing them into one message is what makes a breach
 * impossible to diagnose after the fact (guide, step 0).
 * <p>
 * The {@code detail} sentence is shown to the end user and never names a resource of another
 * clinic, nor says whether one exists (LLD 21 §3.2).
 */
public enum SecurityErrorCode {

	/** The account is not a member of the clinic in the path — L1 refuses (guide 6.1). */
	AUTH_CLINIC_NOT_ASSIGNED("Access denied", "You are not attached to this clinic."),

	/** The account is a member, but not with a role this route accepts. */
	AUTH_ROLE_INSUFFICIENT("Access denied", "Your role does not allow this action."),

	/** A tenant operation ran without a tenant context: fail-closed, never "no filter" (I8). */
	AUTH_CLINIC_CONTEXT_MISSING("Access denied", "The clinic for this operation could not be determined."),

	/** identity-service could not be reached: refuse rather than serve unfiltered (guide 8.3). */
	AUTH_ASSIGNMENTS_UNAVAILABLE("Access denied",
			"Your rights could not be verified right now. Please try again shortly."),

	/** No usable session on a protected route. */
	AUTH_NOT_AUTHENTICATED("Authentication required", "Log in to access this resource.");

	private final String title;

	private final String detail;

	SecurityErrorCode(String title, String detail) {
		this.title = title;
		this.detail = detail;
	}

	public String title() {
		return title;
	}

	public String detail() {
		return detail;
	}

}
