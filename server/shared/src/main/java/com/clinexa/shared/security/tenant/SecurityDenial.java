package com.clinexa.shared.security.tenant;

import java.io.Serial;

import org.springframework.security.access.AccessDeniedException;

/**
 * A {@code 403} that says <em>which</em> of the refusals it is.
 * <p>
 * Extending {@link AccessDeniedException} is what makes it work anywhere in the request: thrown
 * from the tenant filter (which runs inside {@code ExceptionTranslationFilter}), from a service or
 * from a repository guard, it always reaches the same handler and produces the same Problem Details
 * body. Nothing in that body reveals whether the resource exists (LLD 21 §3.2).
 */
public class SecurityDenial extends AccessDeniedException {

	@Serial
	private static final long serialVersionUID = 1L;

	private final transient SecurityErrorCode code;

	public SecurityDenial(SecurityErrorCode code) {
		super(code.detail());
		this.code = code;
	}

	public SecurityDenial(SecurityErrorCode code, Throwable cause) {
		super(code.detail(), cause);
		this.code = code;
	}

	public SecurityErrorCode code() {
		return code;
	}

}
